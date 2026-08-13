package com.campusdeal.service.impl;

import com.campusdeal.entity.PostComments;
import com.campusdeal.mapper.PostCommentsMapper;
import com.campusdeal.service.IPostCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class PostCommentsServiceImpl extends ServiceImpl<PostCommentsMapper, PostComments> implements IPostCommentsService {

}
