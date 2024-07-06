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
- controller: Controller,主要控制访问哪一个url 会触发怎么样的行为,一个数据库表使用一个控制器
- dto: 
- entity: 用于存储数据库表的映射对象,注意其中的注解形式
- mapper: 提供 Service 层底层调用的接口,持久层
- service: 调用 mapper 层的方法,由于使用了 mybatis-plus插件,所以不需要自己写操作数据库的方法
- utils: 各种加密工具等

