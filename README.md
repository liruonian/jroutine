# jroutine

jroutine在java语言的体系下，实现了一套非常简化的GPM模型，可以帮助大家理解协程的基础概念。

## Table of Contents
- [快速入门](#快速入门)
- [主要思路](#主要思路)
  - [操作数栈](#操作数栈)
  - [协程控制](#协程控制)
  - [调度策略](#调度策略)
- [已知问题](#已知问题)

## 快速入门

首先我们有一个`Loop`类，该类实现了`Runnable`接口，其中的`run()`方法就用来实现我们的业务逻辑。可以看到该类的实现很简单，只是通过递归的方式循环打印`i`的值而已，每次递归`i`的值自增1。
```java
package liruonian.jroutine.weave.rewrite;

public class Loop implements Runnable {

    @Override
    public void run() {
        try {
            print(0);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void print(int i) throws InterruptedException {
        Thread.sleep(500);
        System.out.println(Thread.currentThread().getName() + ": " + i++);
        print(i);
    }

}
```

然后我们看下jroutine如何通过协程的方式来执行它。
```java
public class StandardSchedulerTest extends TestCase {

  public void testSubmit() throws Exception {
    // 启动调度器
    StandardScheduler scheduler = new StandardScheduler();
    scheduler.start();

    // 使用ASM类加载器加载业务资源
    WeaverClassLoader classLoader = new WeaverClassLoader(new URL[]{}, new AsmClassTransformer());
    Class<?> clazz = classLoader.loadClass("rewrite.weave.liruonian.jroutine.Loop");

    // 构造协程
    Coroutine coroutine = new Coroutine((Runnable) clazz.newInstance());

    // 开始调度协程
    scheduler.submit(coroutine);

    // 协程挂起
    Thread.sleep(2000);
    System.out.println("coroutine suspend for 2s...");
    scheduler.suspend(coroutine);

    // 协程恢复
    Thread.sleep(2000);
    System.out.println("coroutine resume...");
    scheduler.resume(coroutine);


    Thread.sleep(Integer.MAX_VALUE);

  }

}
```

这段testcase程序大概做了如下的事情：
1. 启动`scheduler`调度器，用于协程的调度，比如通过协程的状态、动作来决定协程是否应该挂起或运行；
2. 创建`WeaverClassLoader`的实例，该classLoader在加载类资源时，会通过字节码增强的方式织入一些代码，这层代码对应用层是透明的，它使得协程变得可控。这是很重要的事情，因为java本身无法"暂停"一条线程，所以当协程在线程上运行时，能否主动中断它的运行是能否调度它的关键；
3. 构造`couroutine`的实例，并通过`scheduler`去`submit`、`suspend`和`resume`协程；

执行后的输出如下所示:
```text
1  JROUTINE-EXECUTOR-0-T1: 0
2  JROUTINE-EXECUTOR-0-T1: 1
3  JROUTINE-EXECUTOR-0-T1: 2
4  coroutine suspend for 2s...
5  coroutine resume...
6  JROUTINE-EXECUTOR-1-T1: 3
7  JROUTINE-EXECUTOR-1-T1: 4
8  JROUTINE-EXECUTOR-1-T1: 5
9  JROUTINE-EXECUTOR-1-T1: 6
10 JROUTINE-EXECUTOR-1-T1: 7
```

对于这段输出，我们重点关注两点：
1. 协程上下文切换：可以看到`Loop`类只是不断打印当前的线程名和`i`的值而已，而`i`的值每次递归后都会自增1。看日志第3~4行，当`i`自增为2时，我们挂起了当前的协程，并且等待了2s时间。在第5行进行了协程的恢复，可以看到递归继续进行了，`i`也延续了挂起前的值，并且应用层并不需要为此做任何修改。
2. 协程调度：在协程暂停前，我们可以看到协程运行的线程是`JROUTINE-EXECUTOR-0-T1`，而协程恢复后，运行线程变成了`JROUTINE-EXECUTOR-1-T1`，这是因为默认采用轮询调度策略的原因，后面我们会再支持其它调度策略。

## 主要思路

### 操作数栈
OperandStack

### 协程控制
协程控制主要依赖asm对字节码进行增强来实现，我们可以对比下字节码修改前后的代码差异来理解协程控制的逻辑。为了方便，我们再对已经很简单的`Loop`类做下减法。
```java
public class Loop implements Runnable {

    // other methods
    // func() {}

    private void print(int i) throws InterruptedException {
        Thread.sleep(500);
        System.out.println(i);
        print(++i);
    }

}
```
在经过字节码增强后，其代码逻辑大致如下：
```java
public class Loop {
    
    private void print(int i) throws InterruptedException {
        CoroutineContext context;
        block9:
        {
            block8:
            {
                // context是协程流转的上下文
                context = CoroutineContext.get();
                if (!(context == null || !context.restoring)) {
                    // anchor是锚点值，当协程恢复时，会根据锚点值来判断协程挂起时程序运行到哪个步骤
                    switch (context.popAnchor()) {
                        case 0: {
                            // 从context中恢复变量
                            i = context.popInt();
                            this = (Loop) context.popObject();
                            break;
                        }
                        case 1: {
                            i = context.popInt();
                            this = (Loop) context.popObject();
                            break block8;
                        }
                        case 2: {
                            i = context.popInt();
                            this = (Loop) context.popObject();
                            break block9;
                        }
                        default: {

                        }
                    }
                }

                Thread.sleep(500);
                // 当协程挂起时，会进入capture的逻辑，此处会将当前方法中的参数值暂存到context中，之后恢复时使用，
                // 此外，此处也会记录锚点值
                if (context != null && context.capturing) {
                    context.pushReference((Object) this);
                    context.pushObject((Object) this);
                    context.pushInt((int) i);
                    context.pushAnchor(0);
                    return;
                }

            }
            System.out.println((int) i);
            if (context != null && context.capturing) {
                context.pushReference((Object) this);
                context.pushObject((Object) this);
                context.pushInt((int) i);
                context.pushAnchor(1);
                return;
            }
        }
        print((int) i++);
        if (context != null && context.capturing) {
            context.pushReference((Object) this);
            context.pushObject((Object) this);
            context.pushInt((int) i);
            context.pushAnchor(2);
            return;
        }
        CoroutineContext.get().done();
    }
}
```

### 调度策略
StandardScheduler

## 已知问题
- 暂未支持多级反馈队列
- 如何判断当前协程已经执行完成