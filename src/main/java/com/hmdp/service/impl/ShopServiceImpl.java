package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //1. redis穿透：不存在的数据在redis中存储为null
        Shop shop = cacheClient.get(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES, this::getById);
//        //2. redis击穿：设置逻辑过期时间，如果逻辑过期时间超时，设置额外线程获取互斥锁新建缓存
//        Shop shop = cacheClient.getWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                RedisConstants.CACHE_SHOP_TTL,
//                TimeUnit.MINUTES, this::getById);
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    /**
     * 更新商铺
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result uodate(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    //尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void clseLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
