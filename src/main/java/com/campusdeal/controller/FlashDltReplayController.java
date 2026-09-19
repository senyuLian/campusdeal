package com.campusdeal.controller;

import com.campusdeal.dto.FlashDltReplayRequest;
import com.campusdeal.dto.Result;
import com.campusdeal.service.FlashDltReplayService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Admin-only DLT replay endpoint; the service repeats the authorization check. */
@Validated
@RestController
@ConditionalOnProperty(prefix = "campusdeal.kafka", name = "enabled", havingValue = "true")
@RequestMapping("/admin/flash-orders/dlt")
public class FlashDltReplayController {

    @Resource
    private FlashDltReplayService replayService;

    @PostMapping("/replay")
    public Result replay(@Valid @RequestBody FlashDltReplayRequest request) {
        return replayService.replay(request);
    }
}
