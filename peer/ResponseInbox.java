package peer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Inbox system to route UDP responses to waiting operations.
 * The async listener deposits messages, and blocking operations retrieve them.
 */
public class ResponseInbox {
    private static final ConcurrentHashMap<String, BlockingQueue<String>> inboxes = new ConcurrentHashMap<>();

    public static void register(String messageType) {
        inboxes.putIfAbsent(messageType, new LinkedBlockingQueue<>());
    }

    /**
     * Deposit a message into the appropriate inbox
     * @return true if deposited, false if no inbox exists
     */
    public static boolean deposit(String messageType, String message) {
        BlockingQueue<String> queue = inboxes.get(messageType);
        if (queue == null) {
            register(messageType);
            queue = inboxes.get(messageType);
        }
        queue.offer(message);
        return true;
    }

    public static String waitFor(String messageType, long timeoutMs) throws InterruptedException {
        BlockingQueue<String> queue = inboxes.get(messageType);
        if (queue == null) {
            register(messageType);
            queue = inboxes.get(messageType);
        }
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public static void unregister(String messageType) {
        inboxes.remove(messageType);
    }
}
