package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.CastHand;
import org.junit.Test;
import java.util.List;

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
    public void everyDoorBreakerMoveUsesItsOwnClipForTheSelectedHand() {
        // 八式各有主手、副手两套剪辑；片段选择只跟随主副手，不能把物理左右手提前镜像一次。
        var direction = new HumanTechniqueAnimations.Direction(0, 0, 0);
        for (String move : new String[]{"knock", "cleave", "ram", "hook", "sweep", "close", "chain", "finale"}) {
            String clip = "door_breaker_" + move;
            String presentation = "meridian_door_breaker:" + clip;
            assertEquals(clip, HumanTechniqueAnimations.resolve(presentation, direction, CastHand.MAIN));
            assertEquals(clip + "_off", HumanTechniqueAnimations.resolve(presentation, direction, CastHand.OFF));
        }
    }

    @Test
    public void playbackFollowsTheCastTempoAndFoldsLatenessIntoTheStartTick() {
        // 原速：从剪辑 500 毫秒处开播、迟到 100 毫秒，起点往回 12 tick。
        assertEquals(new HumanTechniqueAnimations.Playback(988, 1), HumanTechniqueAnimations.playback(1000, 500, 100, 1));
        // 半速：剪辑的 500 毫秒要 1000 毫秒真实时间才走到，再加迟到的 100 毫秒，往回 22 tick。
        assertEquals(new HumanTechniqueAnimations.Playback(978, 0.5f), HumanTechniqueAnimations.playback(1000, 500, 100, 0.5));
        // PA 只能放慢：节奏比原速快、或者倍率不合法，都按原速播。
        assertEquals(new HumanTechniqueAnimations.Playback(990, 1), HumanTechniqueAnimations.playback(1000, 500, 0, 1.5));
        assertEquals(new HumanTechniqueAnimations.Playback(990, 1), HumanTechniqueAnimations.playback(1000, 500, 0, 0));
        // 世界刚开始时起点不为负。
        assertEquals(new HumanTechniqueAnimations.Playback(0, 1), HumanTechniqueAnimations.playback(3, 500, 0, 1));
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

    @Test
    public void yunxiClipsFollowTheFrozenSwordHand() {
        // 四式在同一方向下只由施放快照选择出剑侧。
        var direction = new HumanTechniqueAnimations.Direction(0, 0, 1);
        var clips = List.of("yunxi_wind_thrust", "yunxi_moon_lift", "yunxi_flash_parry", "yunxi_cloud_cleaver");
        for (var hand : CastHand.values()) {
            String suffix = hand == CastHand.OFF ? "_off" : "";
            var expected = clips.stream().map(clip -> clip + suffix).toList();
            var selected = clips.stream()
                    .map(clip -> HumanTechniqueAnimations.resolve("meridian_story:" + clip, direction, hand))
                    .toList();
            assertEquals(expected, selected);
        }
    }

    @Test
    public void piglinSwordMovesFollowTheFrozenWeaponHand() {
        // 绕、抖、弹各有主手、副手两套剪辑；剑在哪只手由起手快照决定，左右镜像交给 PA。
        var direction = new HumanTechniqueAnimations.Direction(0, 0, 0);
        for (String move : new String[]{"wind_around", "quiver", "spring_back"}) {
            String clip = "piglin_sword_" + move;
            String presentation = "meridian_piglin_sword:" + clip;
            assertEquals(clip, HumanTechniqueAnimations.resolve(presentation, direction, CastHand.MAIN));
            assertEquals(clip + "_off", HumanTechniqueAnimations.resolve(presentation, direction, CastHand.OFF));
        }
    }
}
