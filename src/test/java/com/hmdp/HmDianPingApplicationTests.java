package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;


import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Test
    void loadShopData() {
        List<Shop> list = shopService.list();

        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for (Map.Entry<Long, List<Shop>> entry : collect.entrySet()) {
            Long typeId = entry.getKey();

            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
            // 1543688  1750488
            // 1.47mb   1.67M

        }

    }


    @Test
    void testHyperLogLog() {

        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        //统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");

        System.out.println(count);

    }
    @Test
    void testTest() {
        int m = 50;
        int count = 0;
        int num = 0;
        List<Integer> numbers = new ArrayList<>();
        while (true) {
            if (m <= 0) {
                break;
            } else if ((m & 1) ==1){

                num = count == 0 ? 1 : 2 << count-1;
                numbers.add(num);

            }
            count++;
            m >>>= 1;
        }
        numbers.forEach(System.out::println);
    }
}



