package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;  // redistemplate对象
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 查看是否符合验证码,不符合符合错误类
            return Result.fail("手机号码格式错误");
        }
        // 3. 符合发送验证码
        String code = RandomUtil.randomNumbers(6);
        // 4. 保存验证码到 Session 中
        // 验证码保存到 redis中,同时注意设置有效期  2 min 就是时间单位,注意固定的部分就可以使用常量定义
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code , LOGIN_CODE_TTL , TimeUnit.MINUTES);
        // 5. 发送验证码
        log.debug("发送短信验证码成功,验证码: {}",code);  // SLF4j底层提供的一个打印日志的方法
        return null;
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //  用户提交手机号和验证码,先校验手机号之后校验格式是否正确
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 校验验证码
        //  首先取出验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        // 3. 不一致,报错
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误");
        }
        // 4. 一致,就可以根据手机号查询用户
        // 利用 mybatis-plus 查询手机号码
        User user = query().eq("phone", phone).one();
        // 5. 判断用户是否存在
        if (user == null) {
            // 不存在,就可以生成一个新的对象
            // 6. 不存在创建新的用户并且保存
            user = createUserWithPhone(phone);  // 根据手机号创建用户
        }  // 前面一定需要统一 user ,一定要有值
        // 7. 保存用户信息到 session中

        // 7.1 生成一个 token 作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2 将 User对象转化为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        // 7.3 存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((name,value) -> value.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        // 注意此时 Long无法转化为 string ,所以map中所有对象的的value都是 String
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        // 设置有效期,但是如果是这样的话,无论如何session都会在 30min之后过期,所以需要设置不断更新有效期，可以直接在拦截器中更新
        stringRedisTemplate.expire(tokenKey,30,TimeUnit.MINUTES);
        //8 最后返回 token 给前端

        return Result.ok(token);  // 这里不需要返回用户凭证,原因就是已经存储到了 session中,但是返回一个用户凭证那么前端就可以重新重定向页面
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建对象
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2. 存储到数据库表中
        save(user);
        return user;
    }
}
