# 黑马点评项目基本介绍
## 数据库准备
- 数据库表如下:
    
表名|作用  
---|---  
tb_user|用户表  
tb_shop|商品信息表  
tb_user_info|用户详情表  
tb_shop_type|商户类型表  
tb_blog|用户日记表(达人探店日记)  
tb_follow|用户关注表  
tb_voucher|优惠券表  
tb_voucher_order|优惠券的订单表  

## 项目的基本架构
- 单体架构项目,没有使用微服务架构,使用前后端分离的模式,后端部署在Tomcat上,前端部署在 NGINX 服务器端上,同时考虑并发能力,需要构建 tomcat集群,架构图如下:  
![Screenshot_20240706_091411_tv.danmaku.bilibilihd.jpg](img%2FScreenshot_20240706_091411_tv.danmaku.bilibilihd.jpg)
## 启动项目
- 前端项目运行在 nginx 上,访问localhost:8080可以检查前端项目是否启动并且和后端进行通信
- 注意启动后端项目需要首先为 idea 添加 SpringBoot 启动项
## 项目架构分析
- config: 主要存放配置信息,包含mybatis-plus的配置,还有异常处理器的配置
- controller: Controller,主要控制访问哪一个url 会触发怎么样的行为,一个数据库表使用一个控制器,调用 Service层的方法
    传递数据到 Service层,响应数据给前端
- dto: 一些其他的信息的封装,比如响应状态码,响应信息等数据的封装,比如正则表达式等信息,用户数据纯属独享,一般就是对于表格的简化,只会传递需要的信息
- entity: 用于存储数据库表的映射对象,注意其中的注解形式
- mapper: 提供 Service 层底层调用的接口,持久层
- service: 调用 mapper 层的方法,由于使用了 mybatis-plus插件,所以不需要自己写操作数据库的方法
- utils: 各种加密工具等

还是注意统一的开发流程: 首先分析功能需求,之后找到控制这些功能需求的接口的位置,调用 service 层的方法,获取数据进行适当处理返回给前端
service层中的方法首先在接口声明,一般和controller层中的对应方法类似,声明之后找到实现类,发送数据给前端,在实现类中实现业务方法
# 黑马点评项目各部分分析
## 短信登录
[login_through_mes.md](note%2Flogin_through_mes.md)
## 商品查询缓存
[shop_cache_query.md](note%2Fshop_cache_query.md)
## 优惠卷秒杀
[voucher_second_kill.md](note%2Fvoucher_second_kill.md)
## 分布式锁解决多服务器下的线程安全问题
[distributed_lock_use.md](note%2Fdistributed_lock_use.md)
## 秒杀业务的优化
[improve_voucher_seckill.md](note%2Fimprove_voucher_seckill.md)
## 利用 Redis 消息队列优化秒杀业务
[improve_mes_que.md](note%2Fimprove_mes_que.md)
## 达人探店
[publish_shop_note.md](note%2Fpublish_shop_note.md)
## 好友关注功能实现
[make_good_friend.md](note%2Fmake_good_friend.md)
## 附近商铺功能
