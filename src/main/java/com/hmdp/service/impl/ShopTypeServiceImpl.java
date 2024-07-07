package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_LIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryForShopType() {
        // 首先查询缓存
        String  key = SHOP_TYPE_LIST;
        List<String> shopList = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 获取的就是所有商铺的信息
        if(shopList != null && !shopList.isEmpty()) {
            // 可以返回给前端
            // 转化为列表,之后返回给前端
            List<ShopType> shopTypes = new ArrayList<>();
            for (String shop : shopList) {
                // 序列化
                ShopType shopType = JSONUtil.toBean(shop, ShopType.class);
                shopTypes.add(shopType);
            }
            return Result.ok(shopTypes);  // 注意这里排序应该是之前的工作,而不是之后的工作
        }
        // 没有查询到数据
        List<ShopType> shops = query().orderByAsc("sort").list();
        // 之后放入到 Redis 缓存中
        // 首先还是序列化为字符串
        // 直接一个一个放入
        for (ShopType shop : shops) {
            String jsonStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForList().rightPush(key,jsonStr);
        }
        // 最后返回就可以了
        return Result.ok(shops);
    }
}
