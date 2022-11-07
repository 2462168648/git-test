package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {

            //2. 如果不符合，返回错误信息

            return Result.fail("手机号格式错误");

        }

        //3. 如果符合，生成验证码

        String code = RandomUtil.randomNumbers(6);

        //4. 保存验证码到session
        //  session.setAttribute("code",code);

        //4. 保存验证码到redis,有效期5分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5. 发送验证码

        log.debug("发送验证码成功，验证码: "+ code);
        //6. 返回ok

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)) {

            return Result.fail("手机号格式错误");
        }

        //2. 校验验证码
        String requestCode = loginForm.getCode();


        //  从session中获取验证码
        //  String sessionCode = session.getAttribute("code").toString();

        //2.1 从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

        //3. 不一致，报错
        if (!(requestCode.equals(cacheCode))){

            return Result.fail("验证码错误");

        }

        //4. 一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5. 判断用户是否存在
        if (user == null) {

        //6. 不存在，创建用户并保存信息
            user = createUserWithPhone(phone);
        }

        //7. 保存用户信息至redis
        //7.1 随机生存token作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //7.2 将user对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        //将用户转成HashMap存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)-> fieldValue.toString()
                        )
        );

        //7.3 存储
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));


        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userMap);

        //设置用户信息过期时间 3600秒
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,TimeUnit.SECONDS);


        //8. 返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1. 获取用户
        Long userId = UserHolder.getUser().getId();

        //2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();

        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key = RedisConstants.USER_SIGN_KEY+userId+keySuffix;
        //3. 获取今天是当月第几天
        int dayOfMonth = now.getDayOfMonth();

        //4. 写入redis SETBIT KEY OFFSET 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1 ,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1. 获取用户
        Long userId = UserHolder.getUser().getId();

        //2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();

        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key = RedisConstants.USER_SIGN_KEY+userId+keySuffix;
        //3. 获取今天是当月第几天
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands
                        .BitFieldType
                        .unsigned(dayOfMonth))
                .valueAt(0));

        if (result == null || result.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        Long number = result.get(0);
        if (number == null || number == 0) {
            return Result.ok(Collections.emptyList());
        }
        //遍历取二进制位
        int count = 0;
        while (true){
            if((number & 1)==0){
                //等于0说明未签到 直接结束
                break;
            }else {
                count+=1;

            }

            number >>>=1;
        }
        return Result.ok(count);
    }

    /**
     * 通过手机号创建用户信息
     * @param phone 手机号
     * @return user 用户对象
     */
    private User createUserWithPhone(String phone) {
        //1. 创建用户对象
        User user = new User();

        user.setPhone(phone);

        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(8));
        //2. 持久化到数据库
        save(user);
        return user ;
    }
}
