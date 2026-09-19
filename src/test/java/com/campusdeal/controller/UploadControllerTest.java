package com.campusdeal.controller;

import com.campusdeal.entity.UploadAsset;
import com.campusdeal.mapper.UploadAssetMapper;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.utils.LoginInterceptor;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock UploadAssetMapper uploadAssetMapper;
    @Mock AuthorizationService authorizationService;
    @InjectMocks UploadController controller;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "uploadRoot", tempDir.toString());
        ReflectionTestUtils.setField(controller, "maxSizeBytes", 1024 * 1024L);
        ReflectionTestUtils.setField(controller, "allowedContentTypes", "image/png");
        UserDTO user = new UserDTO();
        user.setId(10L);
        UserHolder.saveUser(user);
        lenient().when(authorizationService.requireAuthenticated()).thenReturn(user);
    }

    @AfterEach
    void clearPrincipal() {
        UserHolder.removeUser();
    }

    @Test
    void acceptsDecodedPngAndStoresServerAsset() {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        controller.uploadImage(new MockMultipartFile("file", "evil.jpg", "text/plain", png));
        org.mockito.Mockito.verify(uploadAssetMapper).insert(any(UploadAsset.class));
    }

    @Test
    void rejectsDisguisedContent() {
        assertThatThrownBy(() -> controller.uploadImage(
                new MockMultipartFile("file", "image.png", "image/png", "not-an-image".getBytes())))
                .isInstanceOf(com.campusdeal.exception.ValidationException.class);
    }

    @Test
    void rejectsFilesOverConfiguredSizeBeforeWriting() {
        ReflectionTestUtils.setField(controller, "maxSizeBytes", 2L);

        assertThatThrownBy(() -> controller.uploadImage(
                new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3})))
                .isInstanceOf(com.campusdeal.exception.ValidationException.class);
        verify(uploadAssetMapper, never()).insert(any());
    }

    @Test
    void crossOwnerDeletionIsRejectedBeforeFileMutation() {
        UploadAsset asset = new UploadAsset();
        asset.setAssetId("0123456789abcdef0123456789abcdef");
        asset.setOwnerUserId(11L);
        asset.setRelativePath("blogs/0/0/0123456789abcdef0123456789abcdef.png");
        asset.setStatus("ACTIVE");
        when(uploadAssetMapper.selectById(asset.getAssetId())).thenReturn(asset);
        doThrow(new com.campusdeal.exception.ForbiddenException())
                .when(authorizationService).requireSameUser(11L);

        assertThatThrownBy(() -> controller.deleteUpload(asset.getAssetId()))
                .isInstanceOf(com.campusdeal.exception.ForbiddenException.class);
    }

    @Test
    void traversalDeletionIsRejectedBeforeResolvingOutsideRoot() {
        UploadAsset asset = new UploadAsset();
        asset.setAssetId("0123456789abcdef0123456789abcdef");
        asset.setOwnerUserId(10L);
        asset.setRelativePath("../outside.txt");
        asset.setStatus("ACTIVE");
        when(uploadAssetMapper.selectById(asset.getAssetId())).thenReturn(asset);

        assertThatThrownBy(() -> controller.deleteUpload(asset.getAssetId()))
                .isInstanceOf(com.campusdeal.exception.ValidationException.class);
        verify(uploadAssetMapper, never()).updateById(any());
    }

    @Test
    void absoluteDeletionIsRejectedBeforeFileMutation() {
        UploadAsset asset = new UploadAsset();
        asset.setAssetId("0123456789abcdef0123456789abcdef");
        asset.setOwnerUserId(10L);
        asset.setRelativePath(tempDir.resolve("outside.txt").toString());
        asset.setStatus("ACTIVE");
        when(uploadAssetMapper.selectById(asset.getAssetId())).thenReturn(asset);

        assertThatThrownBy(() -> controller.deleteUpload(asset.getAssetId()))
                .isInstanceOf(com.campusdeal.exception.ValidationException.class);
        verify(uploadAssetMapper, never()).updateById(any());
    }

    @Test
    void anonymousUploadIsRejectedBeforeWritingAsset() {
        doThrow(new com.campusdeal.exception.UnauthorizedException())
                .when(authorizationService).requireAuthenticated();

        assertThatThrownBy(() -> controller.uploadImage(
                new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3})))
                .isInstanceOf(com.campusdeal.exception.UnauthorizedException.class);
        verify(uploadAssetMapper, never()).insert(any());
    }

    @Test
    void noGetMappingDeletesUploads() {
        boolean legacyGetDelete = java.util.Arrays.stream(UploadController.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class));
        org.assertj.core.api.Assertions.assertThat(legacyGetDelete).isFalse();
    }

    @Test
    void legacyGetDeleteEndpointIsUnavailable() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/upload/delete").param("assetId", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void anonymousUploadIsRejectedByHttpInterceptor() throws Exception {
        UserHolder.removeUser();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new LoginInterceptor()).build();

        mvc.perform(multipart("/upload/post")
                        .file(new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3})))
                .andExpect(status().isUnauthorized());
    }
}
