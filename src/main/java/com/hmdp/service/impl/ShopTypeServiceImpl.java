package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取商铺类型列表
     * @return
     */
    @Override
    public Result queryList() {
        //1. 从redis中查询商铺类型
        String typeJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        //2. 存在：返回
        if (typeJson != null && !typeJson.isEmpty()) {
            List<ShopType> typeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(typeList);
        }
        //3. 不存在：从数据库查询商铺类型
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //4. 不存在：返回错误信息
        if (typeList == null && typeList.isEmpty()) {
            return Result.fail("商铺类型为空！");
        }
        //5. 存在：将商铺类型存入redis，返回商铺类型
        typeJson = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY, typeJson,
                RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
