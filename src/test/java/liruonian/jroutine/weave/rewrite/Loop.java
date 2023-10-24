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
