package ru.leymooo.antirelog.manager;

import com.comphenix.protocol.events.PacketContainer;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.leymooo.antirelog.Antirelog;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.util.ProtocolLibUtils;
import ru.leymooo.antirelog.util.VersionUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class CooldownManager {

    private final Antirelog plugin;
    private final Settings settings;
    private final ScheduledExecutorService scheduledExecutorService;
    private final Table<Player, CooldownType, Long> cooldowns = HashBasedTable.create();
    private final Table<Player, CooldownType, ScheduledFuture> futures = HashBasedTable.create();

    private final Map<UUID, Long> protectionUntil = new HashMap<>();

    public CooldownManager(Antirelog plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        if (plugin.isProtocolLibEnabled()) {
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        } else {
            scheduledExecutorService = null;
        }
    }

    public void addCooldown(Player player, CooldownType type) {
        cooldowns.put(player, type, System.currentTimeMillis());
    }

    public void addItemCooldown(Player player, CooldownType type, long duration) {
        if (!plugin.isProtocolLibEnabled()) {
            return;
        }
        int durationInTicks = (int) Math.ceil(duration / 50.0);
        PacketContainer packetContainer = ProtocolLibUtils.createCooldownPacket(type.getMaterial(), durationInTicks);
        ProtocolLibUtils.sendPacket(packetContainer, player);

        ScheduledFuture future = scheduledExecutorService.schedule(() -> {
            removeItemCooldown(player, type);
        }, duration, TimeUnit.MILLISECONDS);
        futures.put(player, type, future);
    }

    public void removeItemCooldown(Player player, CooldownType type) {
        if (!plugin.isProtocolLibEnabled()) {
            return;
        }
        ScheduledFuture future = futures.get(player, type);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            futures.remove(player, type);
        }
        PacketContainer packetContainer1 = ProtocolLibUtils.createCooldownPacket(type.getMaterial(), 0);
        ProtocolLibUtils.sendPacket(packetContainer1, player);
    }

    public void enteredToPvp(Player player) {
        if (player.hasPermission("antirelog.bypass.cooldown")) {
            return;
        }

        for (CooldownType cooldownType : CooldownType.values) {
            int cooldown = cooldownType.getCooldown(settings);
            if (cooldown == 0) {
                continue;
            }
            if (cooldown > 0 && hasCooldown(player, cooldownType, cooldown * 1000)) {
                addItemCooldown(player, cooldownType, getRemaining(player, cooldownType, cooldown * 1000));
            }
            if (cooldown < 0) {
                addItemCooldown(player, cooldownType, 300 * 1000);
            }
        }
    }

    public void removedFromPvp(Player player) {
        for (CooldownType cooldownType : CooldownType.values) {
            int cooldown = cooldownType.getCooldown(settings);
            if (cooldown > 0 && hasCooldown(player, cooldownType, cooldown * 1000)) {
                removeItemCooldown(player, cooldownType);
            }
        }
    }

    public boolean hasCooldown(Player player, CooldownType type, long duration) {
        Long added = cooldowns.get(player, type);
        if (added == null) {
            return false;
        }
        return (System.currentTimeMillis() - added) < duration;
    }

    public long getRemaining(Player player, CooldownType type, long duration) {
        Long added = cooldowns.get(player, type);
        return duration - (System.currentTimeMillis() - added);
    }

    public void remove(Player player) {
        cooldowns.row(player).clear();
        futures.row(player).forEach((ignore, future) -> future.cancel(false));
        futures.row(player).clear();
        removeProtection(player);
    }

    public void clearAll() {
        futures.rowMap().forEach((p, map) -> map.forEach((i, f) -> {
            f.cancel(true);
            removeItemCooldown(p, i);
        }));
        futures.clear();
        cooldowns.clear();
        protectionUntil.clear();
    }

    public Settings getSettings() {
        return settings;
    }

    public void addProtection(Player player, long durationMs) {
        if (durationMs <= 0) return;
        protectionUntil.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
        plugin.getLogger().info("Added protection for " + player.getName() + " for " + (durationMs / 1000) + " seconds");
    }

    public boolean hasProtection(Player player) {
        Long until = protectionUntil.get(player.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            protectionUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void removeProtection(Player player) {
        protectionUntil.remove(player.getUniqueId());
    }

    public enum CooldownType {
        GOLDEN_APPLE(Material.GOLDEN_APPLE, Settings::getGoldenAppleCooldown),
        ENC_GOLDEN_APPLE(VersionUtils.isVersion(13) ? Material.ENCHANTED_GOLDEN_APPLE : Material.GOLDEN_APPLE, Settings::getEnchantedGoldenAppleCooldown),
        ENDER_PEARL(Material.ENDER_PEARL, Settings::getEnderPearlCooldown),
        CHORUS(Material.matchMaterial("CHORUS_FRUIT"), Settings::getСhorusCooldown),
        TOTEM(VersionUtils.isVersion(13) ? Material.TOTEM_OF_UNDYING : Material.matchMaterial("TOTEM"), Settings::getTotemCooldown),
        FIREWORK(VersionUtils.isVersion(13) ? Material.FIREWORK_ROCKET : Material.matchMaterial("FIREWORK"), Settings::getFireworkCooldown);

        public static CooldownType[] values = values();
        Material material;
        Function<Settings, Integer> cooldown;

        CooldownType(Material material, Function<Settings, Integer> cooldown) {
            this.material = material;
            this.cooldown = cooldown;
        }

        public int getCooldown(Settings settings) {
            return cooldown.apply(settings);
        }

        public Material getMaterial() {
            return material;
        }
    }
}