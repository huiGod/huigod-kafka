Apache Kafka源码学习
=================
# 生产者原理
## 元数据管理机制
## 发送数据队列的双重加锁机制
## Producer内存管理机制-CopyOnWriteMap + Dequeu，Batch + Request
## Producer网络请求与响应拆包与粘包处理
## 发送与接收消息的内存队列处理kK
## 发送消息的重试策略

# broker原理

## 网络模型
## HW/LEO 机制
## isr
## 时间轮算法实现的延迟调度机制
## 磁盘.index 写入机制
## 磁盘内存映射机制，为什么.index 使用 mmap，.log 使用 fileChannel

