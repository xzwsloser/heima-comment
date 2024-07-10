# 用户签到功能实现
## BitMap的用法
- 如果记录签到功能,如果使用数据库表存储是否签到那么会十分浪费空间,但是如果使用一个 Bit为记录用户是否签到，也就是 签到就可以记为 1 ,没有签到就可以记录为 0
那么这样的话就可以极大程度上节约内存空间,这就可以使用 Redis中的一种数据结构 BitMap(位图)
- BitMap(位图): Redis底层使用String 来实现 BitMap , String数据结构内存上限就是 512MB = $2^32$ 个bits
- 所以可以使用 用户名 + 月份名 作为 key 就可以了
- BitMap的操作命令有:
  - SETBIT: 向指定位置(offset)存入一个 0 或者 1
  - GETBIT: 获取指定位置的(offset)的bit值
  - BITCOUNT: 统计BitMap中值为1 的bit为的数量
  - BITFIELD: 操作(查询，修改，自增)BitMap中bit数组中的指定位置(offset)的值
  - BITFIELD_RO: 获取BitMap中bit数组的值，并且以十进制的方式返回
  - BITOP: 将多个BitMap的结果做位运算(& | ^)
  - BITOS: 查找bit数组中指定范围的第一个0 或者 1出现的位置
- 使用演示: 
![img_1.png](..%2Fimg%2Fimg_1.png)
## 签到功能实现
- bitMap相关的实现都在string操作的方法中
- 实现方法就是首先利用用户名和当前日期拼接得到key
- 之后利用当前日期得到需要填写的key值就可以了
```java
    @Override
    public Result sign() {
        // 1. 获取登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 4. 获取日期
        // 3. 拼接 key
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + format;
        // 5. 写入 Redis中
        int day = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,day - 1,true);
        return Result.ok();
    }
```
## 签到统计功能实现
- 连续签到次数: 就是从最后一次开始向前统计，直到第一次没有签上就可以了，计算总的签到次数就是连续签到次数
- 可以利用 bitfield获取到所有的签到次数: bitfield key get u\[dayofMonth\] 0
- 如何遍历每一个bit位: 就是可以和 1 做 & 运算就可以得到一个bit位,之后把数字右移动1位就可以得到之后的一位了
- 注意如何使用命令得到num,并且如何利用位运算得到每一位 >>> 表示无符号右移
- 但是只可以获取当前最近的签到次数
```java
    @Override
    public Result countSign() {
        // 1. 获取登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 4. 获取日期
        // 3. 拼接 key
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + format;
        // 5. 写入 Redis中
        int day = now.getDayOfMonth();
        List<Long> results = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0));
        // 从 Redis中查询到截至当前日期的bit位,之后就可以进行遍历算法了,就是不断和 1 做 & 运算之后进行右移就可以了
        if(results == null || results.isEmpty()) {
            return Result.ok(0);
        }
        Long num = results.get(0);
        if(num == null || num == 0) {
            return Result.ok(0);
        }
        // 循环遍历
        int count = 0;
        while(true) {
            if ((num & 1) ==0) {
                break;
            } else {
                count ++;
            }
            num >>>= 1;  // 表示最后的右移
        }
        return Result.ok(count);
    }
```
