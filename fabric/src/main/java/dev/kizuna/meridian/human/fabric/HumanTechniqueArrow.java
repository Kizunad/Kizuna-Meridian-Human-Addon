package dev.kizuna.meridian.human.fabric;

/** 附着在原版箭实体上的射击参数，保留原版实体类型与存档。 */
public interface HumanTechniqueArrow {
    void meridianHumanSetPiercing(byte piercing);
    void meridianHumanShieldCooldown(long millis);
    long meridianHumanShieldCooldown();
}
