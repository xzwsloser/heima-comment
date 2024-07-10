# 达人探店功能实现
## 基本介绍
- tb_blog: 表示探店笔记表,包含笔记中的标题,文字和图片等
- tb_blog_comments: 其他用户对于探店笔记的评价
## 查看探店笔记并且发布探店笔记
- 就是简单的数据库操作
## 完善点赞功能
- 需求:
  - 同一个用户只可以点赞一次,再次点击就会取消点赞
  - 如果当前用户已经点赞,则点赞高亮(前端实现,判断Blog类的属性是否为 isLike属性)
- 实现步骤:
  - 给 Blog 类添加一个isLike字段，标示是否被当前用户点赞
  - 修改点赞功能,使用 Redis 中的 set集合(用户不会重复),没有点过赞的点赞数 + 1 , 已经点过赞的点赞数 -1 
  - 修改根据id查询Blog的业务,判断当前用户是否点赞过,赋值给isLike字段
  - 修改分页查询 Blog业务,判断当前登录用户是否点赞过,赋值给isLike字段
- 代码实现:
```java
    @Override
    public Result likeBlog(Long id) {
        // 判断当前用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + id;
        // 1. 获取当前用户
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)) {
            // 没有点过赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        } else {
            // 2. 如果没有点赞,可以点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 数据库点赞数 + 1
            // 移除用户
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
            // 3.如果已经点赞,取消点赞,用户点赞数 - 1, 把用户从 Redis的set集合中移除
        }


        return Result.ok();
    }
```
## 点赞排行榜
- 需求需要按照时间先后顺序先后排序,返回 TOP5 的用户:
  - 底层可以使用SortSet的数据结构,可以根据 score 值进行排序,键值唯一和 TreeMap类似,可以利用 hash 映射找到元素
- 起始就是首先把前面的判断点赞的功能中使用的set集合换成 zset集合,之后确定排行榜时就可以取出 zset集合中的元素封装成 UserDTO 中的元素就可以了
- 还有一个小的细节就是从 Redis 中查出 id的顺序之后需要按照 id的顺序返回数据,所以数据库中不可以利用 in 进行条件匹配,这里可以使用 FIELD 字段进行条件匹配
```java
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
```
