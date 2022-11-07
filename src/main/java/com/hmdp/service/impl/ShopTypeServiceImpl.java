package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
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
    public Result queryTypeList() {
        List<ShopType> list = null;
        //1. 从缓冲中获取商品类别集合
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String shopTypeCache = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopTypeCache)) {
            //如果存在 直接返回
            list = JSONUtil.toList(shopTypeCache, ShopType.class);
            return Result.ok(list);

        }
        //3. 如果不存在 则查询数据库

        list = query().select("id", "name", "icon").list();

        //  log.debug(list.toString());

        //4. 判断查询数据库结果是否为空
        if (list.isEmpty()) {
            //5. 如果为空，返回查询失败
            return Result.fail("查询不到数据");
        }

        //6. 否则 将数据写入缓存

        shopTypeCache = JSONUtil.toJsonStr(list);

        stringRedisTemplate.opsForValue().set(key, shopTypeCache, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        //7. 返回ok

        return Result.ok(list);
    }
}
