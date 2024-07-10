package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = "follows:" + userId;
        // 1. 判断到底时关注还是取关
        if(isFollow) {
            // 关注取关
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id); // 表示发送的就是关注的人的 Id
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把关注用户的 id放入到 redis 的set集合中
                stringRedisTemplate.opsForSet().add(key,id.toString());
            }
        } else {
            // 取关
//          remove(new QueryChainWrapper<Follow>().eq("user_id",user.getId()).eq("follow_user_id",id));
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if (isSuccess) {
                // 把关注的用户 id从 Redis 中移除
                stringRedisTemplate.opsForSet().remove(key,id.toString()); // 表示取关
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollowed(Long id) {
        Long userId = UserHolder.getUser().getId();
        // 判断用户是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0 );
    }

    @Override
    public Result followCommons(Long id) {
        // 查询共同关注用户
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String targetKey = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, targetKey); // 表示求解交集
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 开始查询用户
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
