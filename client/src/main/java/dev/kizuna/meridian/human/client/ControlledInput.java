package dev.kizuna.meridian.human.client;

import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlIntent;

/** 与 Minecraft 无关的受控输入状态；断流仅停步，收到结束才归还主动输入。 */
public final class ControlledInput {
    private static final long MAX_TTL_TICKS = 4;
    private static final float MAX_PITCH = 90;
    private String session = "";
    private String endedSession = "";
    private long sequence;
    private long latestServerTick = -1;
    private long expiresAt;
    private ControlIntent intent = ControlIntent.getDefaultInstance();

    public boolean receive(ControlIntent update, long localTick) {
        // 丢弃乱序、跨会话旧包和结束后重放；本地超时不依赖客户端与服务端时钟同步。
        String sessionId = update.getSessionId();
        long updateSequence = update.getSequence();
        long serverTick = update.getServerTick();
        boolean ended = update.getEnded();
        boolean sameSession = sessionId.equals(session);
        if (sessionId.isEmpty() || updateSequence <= 0
                || sessionId.equals(endedSession) || serverTick < latestServerTick
                || sameSession && updateSequence <= sequence) {
            return false;
        }
        if (active() && !sameSession && (ended || serverTick == latestServerTick)) {
            return false;
        }
        if (!ended && !valid(update)) {
            return false;
        }
        latestServerTick = serverTick;
        sequence = updateSequence;
        if (ended) {
            endedSession = sessionId;
            session = "";
            intent = ControlIntent.getDefaultInstance();
        } else {
            session = sessionId;
            intent = update;
            expiresAt = localTick + update.getValidUntilTick() - serverTick;
        }
        return true;
    }

    private static boolean valid(ControlIntent update) {
        // 不让非法视角或无界有效期冻结客户端操作。
        long ttl = update.getValidUntilTick() - update.getServerTick();
        float pitch = update.getPitch();
        float forward = update.getForward();
        float sideways = update.getSideways();
        return ttl > 0 && ttl <= MAX_TTL_TICKS && Float.isFinite(update.getYaw())
                && Float.isFinite(pitch) && Math.abs(pitch) <= MAX_PITCH
                && Float.isFinite(forward) && Math.abs(forward) <= 1
                && Float.isFinite(sideways) && Math.abs(sideways) <= 1;
    }

    public boolean active() {
        // 断流期间仍屏蔽主动输入，不能靠丢包逃离控制。
        return !session.isEmpty();
    }

    public boolean allowsKey(String name) {
        // 受控时保留交流与观察键；移动、物品和其他附属键默认屏蔽。
        if (!active()) {
            return true;
        }
        return switch (name) {
            case "key.chat", "key.command", "key.screenshot", "key.togglePerspective",
                    "key.playerlist", "key.fullscreen" -> true;
            default -> false;
        };
    }

    public ControlIntent sample(long localTick) {
        // 超时或结束都输出静止，保留最后视角，避免断流时突兀转头。
        if (!active() || localTick >= expiresAt) {
            return ControlIntent.newBuilder()
                    .setYaw(intent.getYaw())
                    .setPitch(intent.getPitch())
                    .build();
        }
        return intent;
    }

    public void reset() {
        // 退出服务器后清所有序号，下一连接使用自己的时钟。
        session = "";
        endedSession = "";
        sequence = 0;
        latestServerTick = -1;
        expiresAt = 0;
        intent = ControlIntent.getDefaultInstance();
    }
}
