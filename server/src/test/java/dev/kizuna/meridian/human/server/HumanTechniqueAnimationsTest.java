package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.CastHand;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HumanTechniqueAnimationsTest {
    private static final double QUARTER_TURN = 90;

    @Test
    public void playersAndHumanoidsShareDirectionalDash() {
        // 输入始终为世界方向，朝向旋转后仍需选择实体自身的左右前后。
        assertDash("forward", new HumanTechniqueAnimations.Direction(0, 0, 1));
        assertDash("back", new HumanTechniqueAnimations.Direction(0, 0, -1));
        assertDash("left", new HumanTechniqueAnimations.Direction(0, 1, 0));
        assertDash("right", new HumanTechniqueAnimations.Direction(0, -1, 0));
        assertDash("forward", new HumanTechniqueAnimations.Direction(QUARTER_TURN, -1, 0));
        var direction = new HumanTechniqueAnimations.Direction(0, 0, 0);
        assertEquals("technique_charge", HumanTechniqueAnimations.resolve(
                "basic:technique_charge", direction, CastHand.MAIN));
    }

    @Test
    public void deliveredDoorClipsUseTheSelectedHandAndKeepRemainingSwings() {
        // 片段选择只跟随主副手，不能把物理左右手提前镜像一次。
        var direction = new HumanTechniqueAnimations.Direction(0, 0, 0);
        for (String move : new String[]{"knock", "cleave", "ram"}) {
            String clip = "door_breaker_" + move;
            String presentation = "meridian_door_breaker:" + clip;
            assertEquals(clip, HumanTechniqueAnimations.resolve(presentation, direction, CastHand.MAIN));
            assertEquals(clip + "_off", HumanTechniqueAnimations.resolve(presentation, direction, CastHand.OFF));
        }
        for (String move : new String[]{"hook", "sweep", "close", "chain", "finale"}) {
            String presentation = "meridian_door_breaker:door_breaker_" + move;
            assertEquals("attack_main", HumanTechniqueAnimations.resolve(presentation, direction, CastHand.MAIN));
            assertEquals("attack_off", HumanTechniqueAnimations.resolve(presentation, direction, CastHand.OFF));
        }
    }

    private static void assertDash(String suffix, HumanTechniqueAnimations.Direction direction) {
        // 以完整内容 ID 进入公开映射，与两个 Fabric 宿主的入口一致。
        assertEquals("technique_dash_" + suffix, HumanTechniqueAnimations.resolve(
                "basic:technique_dash", direction, CastHand.MAIN));
    }

    @Test
    public void wayfarerClipsFollowTheFrozenWeaponHand() {
        // 九式与训练各自保留原片段，副手只加后缀，物理左右手交给动画运行时。
        var direction = new HumanTechniqueAnimations.Direction(0, 0, 0);
        for (String move : new String[]{"full_draw", "pearls", "bow_strike", "repeater", "gale", "kneeling",
                "parthian", "arrow_rain", "convergence", "dry_draw"}) {
            String clip = "wayfarer_" + move;
            String presentation = "meridian_wayfarer:" + clip;
            assertEquals(clip, HumanTechniqueAnimations.resolve(presentation, direction, CastHand.MAIN));
            assertEquals(clip + "_off", HumanTechniqueAnimations.resolve(presentation, direction, CastHand.OFF));
        }
    }
}
