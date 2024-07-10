# 搜索附近商铺功能
## 了解 GEO 数据结构
- GEOADD: 添加一个地理空间位置,包含经度，纬度，值等信息
- GEODIST: 计算指定的两个点之间的距离并且返回
- GEOHASH: 把指定的member 的坐标转换为 Hash字符串形式返回
- GEOPOS: 返回指定的 member 的坐标
- GEORADIUS: 指定圆心，半径，找到这一个圆内包含的所有 member,并且按照和圆心之间的距离排序之后返回，但是现在已经废弃了
- GEOSEARCH: 在指定的范围内搜索memeber,并且按照和指定点之间的距离排序之后返回，范围可以是圆形或者矩形
- GEOSEARCHSTORE: 和上面一个功能相同，不过可以把结果存储到一个指定的key里面
- 其实底层就是利用了 SortedSet,同时利用 hash值存储了位置相关的信息
- 真实使用情况:
![img.png](..%2Fimg%2Fimg.png)
### 导入店铺信息到GEO 中
- 如果需要进行标签过滤，可以按照商户类型进行分组，类型相同的商铺作为同一组，把typeId作为key存储到同一个 GEO 集合中
- 可以利用单元测试把所有的店铺信息添加到数据库中
```java
    @Test
    void loadGEO() {
        // 1.  首先查询店铺信息
        List<Shop> list = shopService.list();
        // 2. 把店铺信息进行分组，id一致的放在一个集合中
        // 可以使用 Map集合
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 分好组之后就可以分批写入到 Redis中了
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }

    }
```
### 实现附近商品功能
- 代码演示如下:
- 梳理一下逻辑,首先前端会传来用户的x,y坐标，以及选择的店铺类型，还有当前需要的页码值
- 后端首先判断是否需要利用距离进行排序,如果不需要根据距离排序那么就可以直接查询记录并且返回
- 如果需要排序，那么就可以利用 typeId 从redis中读取数据，利用geo中的search方法找到距离用户5000m位置以内的商铺放在集合中
- 之后遍历得到ids，利用ids查询出店铺信息并且利用查到的距离信息返回给前端就可以了
```java
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 首先判断是否需要根据坐标查询
        if(x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3. 查询 Redis , 按照距离排序，分页，结果
        String key = SHOP_GEO_KEY + typeId;
        // 4. 解析出 id
        GeoResults<RedisGeoCommands.GeoLocation<String>> search =
                stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        // 表示进行搜索并且进行查询
        if(search == null ) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent();
        if(content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        // 进行截取,从 from 到 end
        List<Long> ids = new ArrayList<>(content.size());
        Map<String,Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(s -> {
            // 获取 店铺 id
            String shopIdStr = s.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取到距离
            Distance distance = s.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        // 5. 查询 id
        // 但是注意有序
        String jsonStr = StrUtil.join(",",ids);
        List<Shop> list = query().in("id", ids).last("ORDER BY FIELD(id," + jsonStr + ")").list();
        // 存储到店铺中
        for (Shop shop : list) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());  // 就是距离
        }
        return Result.ok(list);
    }

```