# 实现好友关注的功能
## 关注和取关
- 页面加载时会发送请求判断是否关注用户,点击关注时就会发送请求到后端
- 点击时发送请求到后端,如果没有关注后端就在表中添加关注的人的字段,如果关注了就可以在表中移除关注的人
```java
   @Override
    public Result follow(Long id, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 1. 判断到底时关注还是取关
        if(isFollow) {
            // 关注取关
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id); // 表示发送的就是关注的人的 Id
            save(follow);
        } else {
            // 取关
//          remove(new QueryChainWrapper<Follow>().eq("user_id",user.getId()).eq("follow_user_id",id));
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",id));
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
```
## 共同关注
- 其实就是求解两个用户关注列表的交集,所以可以使用Redis中的 set 集合求解交集
- 所以需要关注用户时把用户放在Redis中
- 实现方法:
```java
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
```
## 关注推送
- 关注推送也叫做 Feed 流,相当于直接进行投喂信息给用户
- 传统模式就是: 用户 寻找自己需要的内容
- Feed模式就是: 利用内容匹配用户(但是现在的软件利用Feed流技术却恰得其反)
- Feed常见的两种模式:
  - TimeLine: 不做内容的筛选,简单的按照内容发布时间排序, 常用于好友或者关注,比如朋友圈
    - 优点: 信息全面,不会有缺失,并且实现比较简单
    - 缺点: 信息噪音多,用户不一定感兴趣,内容获取效率低下
  - 智能排序: 根据智能算法屏蔽掉违规的,用户不感兴趣的内容,推送用户感兴趣的内容:
    - 优点: 投喂用户感兴趣的内容,用户粘性更高
    - 缺点: 如果算法不精确,可能起到反作用
- TimeLine 模式的三种实现方法:
  - 拉模式(读扩散): 时间延时(发送消息到发件箱,需要读取时就可以拉取发件箱中的消息)
  - 推模式(写扩散): 内存占用大(写下消息时直接发送消息到每一个人的收件箱就可以了)
  - 推拉结合(读写结合): 关注少的使用推模式,粉丝多的人,对于活跃粉丝(可以使用推模式),但是对于普通粉丝就可以使用拉模式
![Screenshot_20240710_111410_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240710_111410_tv.danmaku.bilibilihd.jpg)
- 三种模式的对比
![Screenshot_20240710_111607_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240710_111607_tv.danmaku.bilibilihd.jpg)
- 拉模式
![Screenshot_20240710_111858_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240710_111858_tv.danmaku.bilibilihd.jpg)
- 推模式(最后决定使用推模式)
![Screenshot_20240710_111959_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240710_111959_tv.danmaku.bilibilihd.jpg)
### 利用推模式实现推送功能
- 需求:
  - 修改新增探店笔记业务,为保存 blog 到数据库的同时,推动到粉丝的收件箱
  - 收件箱满足可以根据时间戳排序,必须使用 Redis 的数据结构实现
  - 查询收件箱时,可以实现分页查询
- 按照时间排序可以使用 List 或者 SortedSet , List可以按照角标查询,同时 Sorted set 可以根据排名实现分页
- Feed流中的数据会不断更新,所以数据的角标也会不断发生变化,因此不可以利用传统的分页模式,这就是 Feed流的分页问题(会重复读取)
![Screenshot_20240710_112743_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240710_112743_tv.danmaku.bilibilihd.jpg)
- 但是可以利用滚动分页的模式,就是每一次记录上一次查询的最小 ID, 之后从上一次记录的 ID 开始查询,但是list不支持从哪一个角标开始查询
- SortedSet 支持按照 score 范围进行查询,所以可以实现滚动分页
![Screenshot_20240710_113007_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240710_113007_tv.danmaku.bilibilihd.jpg)
- 利用 Feed 流进行推送的代码演示：
```java

```
## 利用滚动分页查询收件箱
- zrevrange 表示根据角标查询 zrevrange key min max,但是利用角标进行查询时就会发生一条数据重复查询的问题(是降序排列的)
- 如果利用分数进行查询可以利用如下指令:
  - ZREVRANGEBYSCORE key max min \[WITHSCORE\] LIMIT offset(偏移量) count(查询条数)
- 所以整理一下,第一次max可以给成最大值,min固定为最小值,每一次查询时记录查询得到的数字的最小值，得到最小值之后作为下一次查询的最大值
，但是注意除了第一次查询，之后的查询中可以把 limit中的offset设置为 1 表示从后面一个开始查询
- 但是另外一种特殊情况就是如果存在相同的分数，那么上面的offset就会发生问题，第一次来 offset给0 ，下一次的offset取决于上一次最小值一样的元素个数
- 滚动分页查询参数：
  - max  当前时间戳 | 上一次查询中的最小时间戳
  - min 0
  - offset  第一次查询是为 0 | 在上一次的结果中,和最小值一样的元素个数
  - count  3
- 所以每一次需要返回 offset , minTime 还有List<Blog> 表示小于指定时间戳的笔记集合
### 滚动分页功能实现
- 首先明确一点,当探店笔记发布时就会首先在redis中存入关注了这一个人的粉丝的ID,相当于把博客放入到了粉丝的收件箱中，这一个行为出现在了利用feed流推送信息的过程中
- 注意数据的封装和最小时间的获取
```java
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
```
