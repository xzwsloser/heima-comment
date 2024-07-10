package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.ScrollResult;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.aspectj.weaver.ast.Var;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询 Blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 查询 blog 相关的用户
        createBlogUser(blog);
        // 判断是否点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            return ; // 用户没有登录
        }
        Long userId = user.getId();
        String key = "blog:liked:" +  blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null); // 此时就表示没有点赞
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.createBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 判断当前用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + id;
        // 1. 获取当前用户
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            // 没有点过赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        } else {
            // 2. 如果没有点赞,可以点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 数据库点赞数 + 1
            // 移除用户
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
            // 3.如果已经点赞,取消点赞,用户点赞数 - 1, 把用户从 Redis的set集合中移除
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 查询 top5 的用户
        // zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", ids);
        // 表示利用 FIELD 字段进行按照 ids 进行排序
        List<User> users = userService.query().in("id", ids).last("ORDER BY FIELD(id," + join + ")").list();
        // 处理 users
        List<UserDTO> userDTOs = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());;
        return Result.ok(userDTOs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 每一次把blog的基本信息保存到 SortedSet 中
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存
        boolean isSuccess = save(blog);
        // 查询这一个作者的所有粉丝
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 查看笔记作者的所有粉丝
        // select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        // 进行推送
        for (Follow follow : follows) {
            // 获取粉丝 id
            Long followUserId = follow.getUserId();
            // 推送到
            String key = FEED_KEY + followUserId;
            // 注意这里的key就是标记了粉丝的 ID ,并且注意之后的 blog.getId() 就是粉丝的收件箱中的所有 blog的标识
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        // 实现查询的逻辑
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 可以根据 Id 获取到 userId
        String key  = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        // 3. 解析数据,blogId,minTime(时间戳),offset
        if(tuples == null || tuples.isEmpty()) {
            // 获取 id
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取到分数
            long time =  tuple.getScore().longValue();
            if(time == minTime) {
                os ++;
            } else {
                minTime = time;
                os = 1; // 表示此时初始化,前面作废，注意后面的时间越来越小
            }
        }
        // 还是需要保证有序
        // 4. 根据id查询blog
        String idStr = StrUtil.join(",", ids);  // 表示进行拼接
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 查询是否被点赞
        for(Blog blog : blogs) {
            // 查询相关用户
            createBlogUser(blog);  // 表示把用户信息关联到 blog信息
            isBlogLiked(blog);  // 博客是否被点过赞
        }
        // 5. 封装结果并且返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void createBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
