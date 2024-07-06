# 短信功能实现
## 基于 Session 实现登录
- 基于 session 登录的流程图
![Screenshot_20240706_100711_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_100711_tv.danmaku.bilibilihd.jpg)
自己描述一遍流程：
- 发送短信验证码流程:
  - 首先用户提交手机号,校验手机号是否正确,如果不符合就重新添加手机号,如果符合要求就生成验证码,同时注意把验证码存在Session里面(会话域对象)
  - 如果验证码符合要求那么就可以在数据库中根据手机号查询用户信息,如果没有用户信息,那么就需要进入到注册的流程,如果存在那么就可以直接把用户信息保存到 Session 中
  - 校验用户登录状态: 首先用户携带的 cookie 一定会携带 ID 只是 Session,所以只用根据 cookie 从 Session 中获取用户信息,存储到 TheadLocal 中
    这是因为,每一个 请求就相当于一个线程,线程中的多个部分可能用到用户信息,所以需要使用 ThreadLocal 这一个类,如果没有用户就可以进行拦截,回到第一步
- 发送短信验证码的代码片段如下:
```java
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
        session.setAttribute("code",code);
        // 5. 发送验证码
        log.debug("发送短信验证码成功,验证码: {}",code);  // SLF4j底层提供的一个打印日志的方法
        return null;
    }
```
### 实现短信验证码登录和注册功能
- 注意发送短信验证码和校验登录的差别,校验登录是发送短信验证码之后的行为,利用 POST 请求发送,发送验证码和手机号码
```java
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
        Object cacheCode = session.getAttribute("code");
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
        session.setAttribute("user",user);
        return Result.ok();  // 这里不需要返回用户凭证,原因就是已经存储到了 session中,但是返回一个用户凭证那么前端就可以重新重定向页面
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
```
### 校验登录功能
- 利用 SpringMVC 中的拦截器进行登录校验的功能,拦截器拦截的信息传递给controller层,同时注意保证线程安全问题,所以可以把信息存储到 ThreadLocal中
- 注意 SpringMVC 中拦截器组件的配置方式,就是直接实现 Interceptor接口,之后 ctrl + o 实现接口中的方法配置相应的配置信息就可以了
- 注意如何配置拦截器拦截对象,直接利用配置类操作

- 拦截器写法:
```java
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取 Session
        HttpSession session = request.getSession();
        // 2. 获取 session 中的用户
        User user = (User)session.getAttribute("user");
        // 3. 判断用户是否存在
        if (user == null) {
            // 4. 不存在就可以拦截
            response.setStatus(401);  // 返回状态码
            return false; // 表示拦截
        }
        // 5. 存在就可以保存到 ThreadLocal中
        UserDTO userDTO = new UserDTO();
        userDTO.setIcon("icon");
        userDTO.setId(1L);
        userDTO.setNickName(user.getNickName());
        UserHolder.saveUser(userDTO);
        // 6.最后放行就可以了
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
```
- 拦截器配置方法
```java
public class LoginInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    // 1. 获取 Session
    HttpSession session = request.getSession();
    // 2. 获取 session 中的用户
    User user = (User)session.getAttribute("user");
    // 3. 判断用户是否存在
    if (user == null) {
      // 4. 不存在就可以拦截
      response.setStatus(401);  // 返回状态码
      return false; // 表示拦截
    }
    // 5. 存在就可以保存到 ThreadLocal中

    UserHolder.saveUser(BeanUtil.copyProperties(user,UserDTO.class));
    // 6.最后放行就可以了
    return true;
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
    // 移除用户
    UserHolder.removeUser();
  }
}

```
- controller层相关配置
```java
    @GetMapping("/me")
    public Result me(){
        // TODO 获取当前登录的用户并返回
        // 只用从 TheadLocal中取出用户就可以了
        UserDTO user  = UserHolder.getUser();
        return Result.ok(user);
    }
```
- UserDTO 的作用就是防止敏感信息传递给前端,所以需要利用用户信息传递对象UserDTO , 可以使用 ctrl + R 就可以进行查找替换信息了
- 可以利用 BeanUtil 进行对象属性的拷贝
## Session 登录中可能会出现的问题
### 集群Session共享问题
为了应对高并发的场景,往往需要横向扩展tomcat服务器,但是不同的 tomcat服务器不可以共享同一个 session 对象,如果利用 session
对象在不同的服务器之间拷贝的方式,可能会造成空间浪费和访问延时等问题,所以 session 的替代方案如下:
- 需要实现数据共享
- 内存存储
- key,value结构
所以可以使用 Redis 存储原来 session 中的数据,Redis完美满足了这些需求,这就是 Redis的第一个作用
## 基于 Redis 实现 共享 Session 登录
- 就是不是把code和user存储到 session 中,而是存储到 Redis 数据库中,但是 Redis中的key值如何确定? Redis 中每一台tomcat服务器中的key都不应该相同
所以需要可以使用 每一个用户独特的一个属性,比如 手机号码或者 UID 作为 Redis中的key值
- 这里可以使用 Hash 结构存储单个字段,原因就是对于单个字段做增删改查很方便并且占用内存比较少,这里可以使用一个随机的 token作为key,注意此时需要返回 token到前端
,此时可以把token返回给前端,前端记录token数据,下一次请求时直接请求头中携带 token 就可以了
- 具体流程图如下:
![Screenshot_20240706_115237_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_115237_tv.danmaku.bilibilihd.jpg)![img.png](img.png)
![Screenshot_20240706_115310_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_115310_tv.danmaku.bilibilihd.jpg)![img.png](img.png)
### 利用 Redis 实现 Session 登录方法的梳理
- 客户端拿到手机号并且校验手机号之后,利用手机号加上特点的前缀,生成 Redis 中的 key,之后利用Key 和验证码存储到 Redis 数据库中
,从而达到了发送验证码的目的,注意设置验证码的时效
- 收集到验证码之后,校验手机号并且从 Redis 中取出验证码,和用户输入的验证码做一个对比,如果正确,从mysql数据库中取出数据,如果数据不存在,利用手机号码并且随机生成昵称,
利用 BeanUtil.copyProperties 方法把user 对象转换为 UserDTO 对象,之后把 UserDTO 对象存入到 Redis中,注意使用 Hash的形式存储,
利用 stringRedistemplate.opsForHash().putAll() 方法存入key 和map集合就可以存入对象了,这里的一个细节就是如何处理 Long 和 String 的转换问题
另外只要涉及到 Redis的部分一定需要考虑时效性,这里需要用户一登陆就更新过期时间,所以可以在拦截器中更新过期时间,注意此时的key就是自动生成的 token
还要把 token发送给前端
- 访问其他页面是,前端把 token存放在请求头中,后端拦截器利用 request.getHeader("")取得请求头,之后利用 token从 Redis中取出数据,取出数据之后就可以放入到 ThreadLocal中
- 之后访问主页的之后,就可以从 TheadLocal中取出数据返回给前端
- 代码实现如下:
IUserService 
```java
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

```
LoginInterceptor
```java
public class LoginInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;  // 注意由于拦截器没有纳入到 spring 管理所以不可以直接注意
    // 这里可以直接利用被spirng管理的类中的StringRedisTemplate对象
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取 请求头中的 token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            response.setStatus(401);
            return false; // 表示进行拦截
        }
        // 2. 基于 TOKEN 获取到 redis 中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        // 3. 判断用户是否存在
        if (userMap.isEmpty()) {
            // 4. 不存在就可以拦截
            response.setStatus(401);  // 返回状态码
            return false; // 表示拦截
        }
        // Hash数据转换为 UserDTO 对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 5. 存在就可以保存到 ThreadLocal 中
        UserHolder.saveUser(userDTO);
        // 刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token , LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 6.最后放行就可以了
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}

```
- 思考：
  - 有 Redis 的地方一定需要考虑时效性
  - Redis替代 session的原因：
    - 选择合适的数据结构 (String ? , Value ?)
    - 选择合适的 key (token ? phone ?)
    - 选择合适的存储粒度 (存数据的基本信息,有效期)
  - 最后注意 stringRedistemplate 等模板解析器,有一些只可以解析特定规则的成员变量
### 登录拦截器的优化 
- 问题就是: 登录拦截器就是拦截需要登录的页面,但是如果时不用的登录的页面,那么 Redis 中的数据就无法完成刷新
- 解决方案就是再加一个拦截器,专门用于刷新 token
刷新 token 
```java
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
       String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true; // 放行
        }
        // 开始查询数据
        String key = LOGIN_USER_KEY + token;
        // 查询数据
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()) {
            return true;
        }
        // 利用map填充属性
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 放入到 TheadLocal中
        UserHolder.saveUser(userDTO);
        // 设置过期时间
        stringRedisTemplate.expire(key , LOGIN_USER_TTL , TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
      UserHolder.removeUser();
    }
}

```
登录拦截器
```java
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从 TheadLocal中取出对象
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

}
```
配置拦截器
```java
@Configuration
public class MVCConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注意控制执行顺序
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
                "/user/code",
                "/user/login",
                "/upload/**",
                "/blog/hot",
                "/shop/**",
                "/voucher/**"
        ).order(1);    // 注意优先级越小先执行,只要把user放入到了 ThreadLocal里面才可以继续执行后面的步骤
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
```
