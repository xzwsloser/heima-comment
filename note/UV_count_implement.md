# UV统计的实现
## HyperLogLog的用法
- UV: 表示独立访客,也叫做独立访问量,是通过互联网访问,浏览这一个往往有的自然人。1天内同一个用户多次访问这一个网站也就只会记录一次
- PV: 也叫做页面访问量或者点击量,用户每访问一个页面,记录1次PV,用户多次打开页面,则记录多次PV,往往用于衡量用户网站的流量
- 一般来说 PV 比较大 UV 
- UV 统计: UV统计在服务端做起来会十分麻烦,因为需要判断这一个用户是否过时了,需要将统计过的用户信息保存,但是如果每一个访问的用户都保存在Redis中,数据量就会十分恐怖
- HyperLogLog(HLL)是从Loglog中派生出来的一种概率算法,用于确定比较大的的集合的基数,但是不需要存储所有值
- Redis中的HLL 是基于 string 结构实现的,单个 HLL,的内存永远小于16kb，但是测量结果有概率性,有小于 0.81% 的误差，并且可以去重
- 相关的命令:
  - PFADD key element \[element ...\]  表示插入数据，但是可能不会真正保存数据
  - PFCOUNT key 表示统计key中元素的值
  - PFMERGE destkey sourcekey 表示key中元素个数的合并
![img_2.png](..%2Fimg%2Fimg_2.png)
## 实现UV统计
- 直接利用 UV 统计百万用户的值
- 测试代码如下:
```java
    @Test
    public void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hl4",values);
            }
        }
        // 统计数量
        Long size = stringRedisTemplate.opsForHyperLogLog().size("hl4");
        System.out.println("统计得到的数量为:" + size);
    }
}
```

