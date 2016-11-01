package com.chrome.util;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.accessibility.AccessibleText;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.lang.Thread.currentThread;
import static java.time.Instant.now;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Collections.unmodifiableList;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.toList;


public class ChromeUtil {
    private static final int MAX_CLIENT_NUM = 100;
    private static final int MINUTES_OF_WAIT_ONE_REQ_FINISH = 5;
    private static final LinkedBlockingQueue<CefClient> IDLE_CLIENT_QUEUE = new LinkedBlockingQueue<>();
    private static final Map<CefClient, AtomicReference<ChromeUtil>> CLIENT_MAP = new HashMap<>();
    private static final AtomicInteger RUN_COUNT = new AtomicInteger(0);
    private static final AtomicInteger REQ_COUNT = new AtomicInteger(0);
    private static final AtomicLong TOTAL_RUN_TIME = new AtomicLong(0);
    private static final AtomicLong COUNT = new AtomicLong(0);
    private final List<String[]> list = new CopyOnWriteArrayList<>();
    private final AtomicInteger loadCount = new AtomicInteger(0);
    private final ArrayBlockingQueue<List<String[]>> blockingQueue = new ArrayBlockingQueue<>(1);
    private CefClient client = null;
    private int expectLoadTimes = 0;
    private JDialog devToolsDialog;

    private ChromeUtil(int expectLoadTimes) {
        this.expectLoadTimes = expectLoadTimes;
    }

    public static void main(String[] args) {
        ExecutorService es = newCachedThreadPool();
        IntConsumer submitOneTask = theTimesCount -> {
            Runnable runnable = () -> task(args[2], parseInt(args[1]), theTimesCount);
            es.submit(runnable);
        };
        IntStream.range(0, parseInt(args[0])).parallel().forEach(submitOneTask);
    }

    private static void task(String theUrl, int expectLoadTimes, int i) {
        System.out.println("执行次数---");
        List<String[]> theSource = getTheSource(theUrl, expectLoadTimes);
        theSource.forEach(x -> System.out.println("\n第" + (i + 1) + "次访问的第" + REQ_COUNT.incrementAndGet() +
                "个请求\n请求的URL是：" + x[0] + "\n内容是：" + x[1]));
        System.out.println("执行第" + (i + 1) + "次的结果大小为：" + theSource.size());
        Thread thread = Thread.currentThread();
        System.out.println((thread.getThreadGroup().getName() + ":" + thread.getName()) + "执行第" + (i + 1) + "次");
        System.out.println("总计执行了" + RUN_COUNT +
                "次，共耗时" + (TOTAL_RUN_TIME.get() / 60000) + "分钟,平均耗时"
                + (TOTAL_RUN_TIME.get() / RUN_COUNT.get()) + "毫秒");
    }

    private static String cutString(String str, int begin, int len) {
        return str == null ? "" : str.substring(begin, min(str.length(), len));
    }

    public static List<String[]> getTheSource(String url, int expectLoadTimes) {
        RUN_COUNT.incrementAndGet();
        Instant start = now();
        ChromeUtil chromeUtil = new ChromeUtil(expectLoadTimes);
        getCefClient(chromeUtil);
        CefBrowser browser = chromeUtil.client.createBrowser(url, false, false);
        browser.getUIComponent().paint(new DebugGraphics());
        //chromeUtil.openDevTools(browser);
        try {
            List<String[]> poll = chromeUtil.blockingQueue.poll(MINUTES_OF_WAIT_ONE_REQ_FINISH, MINUTES);
            //IDLE_CLIENT_QUEUE.put(chromeUtil.client);
            browser.close();
            //chromeUtil.client.onBeforeClose(browser);
            Set<String> seen = new HashSet<>();
            poll = poll == null ? chromeUtil.list : poll;
            Predicate<String[]> distinct = x -> seen.add(x[0] + x[1]);
            List<String[]> ret = unmodifiableList(poll.stream().filter(distinct).collect(toList()));
            TOTAL_RUN_TIME.addAndGet(start.until(now(), MILLIS));
            return ret;
        } catch (InterruptedException e) {
            TOTAL_RUN_TIME.addAndGet(start.until(now(), MILLIS));
            currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    private static synchronized void getCefClient(ChromeUtil chromeUtil) {
        if (CLIENT_MAP.size() < MAX_CLIENT_NUM && IDLE_CLIENT_QUEUE.isEmpty()) {
            chromeUtil.createNewCefClient();
            System.out.println("create new client\n已经创建client数量--"+CLIENT_MAP.size());
        } else {
            System.out.println("get from catch\n从缓存中拿到了"+COUNT.incrementAndGet()+"次");
            chromeUtil.getFromCache();
        }
    }

    private void getFromCache() {
        try {
            client = IDLE_CLIENT_QUEUE.take();
            CLIENT_MAP.get(client).set(this);
        } catch (InterruptedException e) {
            currentThread().interrupt();
        }
    }

    private void createNewCefClient() {
        AtomicReference<ChromeUtil> ref = new AtomicReference<>();
        ref.set(this);
        CefLoadHandlerAdapter handler = new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                browser.getText(s -> getBrowserSource(isLoading, s, browser, ref));
               //browser.getSource(s -> getBrowserSource(isLoading, s, browser, ref));
            }
        };
        CefApp instance = CefApp.getInstance(new String[]{"--request-context-per-browser=true"});
        client = instance.createClient().addLoadHandler(handler);
        CLIENT_MAP.put(client, ref);
    }

    private synchronized void getBrowserSource(boolean isLoading, String s, CefBrowser browser, AtomicReference<ChromeUtil> reference) {
        System.out.println(browser.getURL()+"---loading="+isLoading+"\n");
        if (isLoading){
            return;
        }
        String url = browser.getURL();
        ChromeUtil chromeUtil = reference.get();
        List<String[]> theList = chromeUtil.list;
        theList.add(new String[]{url, s});
        if (chromeUtil.loadCount.incrementAndGet() == expectLoadTimes) {
            try {
                chromeUtil.blockingQueue.put(theList);
            } catch (InterruptedException e) {
                currentThread().interrupt();
            }
        }
    }



    public void openDevTools(CefBrowser cefBrowser)
    {
        if (devToolsDialog == null)
        {
            devToolsDialog = new JDialog();
            //devToolsDialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            //devToolsDialog.setSize(800, 600);
            devToolsDialog.add(cefBrowser.getDevTools().getUIComponent());
        } else
        {
            devToolsDialog.removeAll();
            devToolsDialog.add(cefBrowser.getDevTools().getUIComponent());
            devToolsDialog.validate();
            devToolsDialog.getContentPane().repaint();

        }
        devToolsDialog.setVisible(false);
        System.out.println("DevTool has opened");
    }


}
