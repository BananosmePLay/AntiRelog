package ru.leymooo.antirelog;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.codemc.worldguardwrapper.WorldGuardWrapper;
import ru.leymooo.annotatedyaml.Configuration;
import ru.leymooo.annotatedyaml.ConfigurationProvider;
import ru.leymooo.annotatedyaml.provider.BukkitConfigurationProvider;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.listeners.CooldownListener;
import ru.leymooo.antirelog.listeners.EssentialsTeleportListener;
import ru.leymooo.antirelog.listeners.PvPListener;
import ru.leymooo.antirelog.listeners.WorldGuardListener;
import ru.leymooo.antirelog.manager.BossbarManager;
import ru.leymooo.antirelog.manager.CooldownManager;
import ru.leymooo.antirelog.manager.PowerUpsManager;
import ru.leymooo.antirelog.manager.PvPManager;
import ru.leymooo.antirelog.util.ProtocolLibUtils;
import ru.leymooo.antirelog.util.Utils;
import ru.leymooo.antirelog.util.VersionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

public class Antirelog extends JavaPlugin implements CommandExecutor, TabCompleter {
    private Settings settings;
    private PvPManager pvpManager;
    private CooldownManager cooldownManager;
    private boolean protocolLib;
    private boolean worldguard;

    @Override
    public void onEnable() {
        loadConfig();
        pvpManager = new PvPManager(settings, this);
        detectPlugins();
        cooldownManager = new CooldownManager(this, settings);
        if (protocolLib) {
            ProtocolLibUtils.createListener(cooldownManager, pvpManager, this);
        }
        getServer().getPluginManager().registerEvents(new PvPListener(this, pvpManager, settings), this);
        getServer().getPluginManager().registerEvents(new CooldownListener(this, cooldownManager, pvpManager, settings), this);

        if (getCommand("antirelog") == null) {
            getLogger().warning("Command 'antirelog' not found in plugin.yml!");
        }

        getLogger().info("AntiRelog enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (pvpManager != null) {
            pvpManager.onPluginDisable();
        }
        if (cooldownManager != null) {
            cooldownManager.clearAll();
        }
        getLogger().info("AntiRelog disabled successfully!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antirelog.admin")) {
            sender.sendMessage(Utils.color("&cУ вас нет прав для использования этой команды."));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Utils.color("&b=== &lAntiRelog Help &r&b==="));
            sender.sendMessage(Utils.color("&e/antirelog reload &7- Перезагрузить конфиг"));
            sender.sendMessage(Utils.color("&e/antirelog stop <игрок|all> [время] &7- Остановить ПВП"));
            sender.sendMessage(Utils.color("&e/antirelog give <игрок1> <игрок2> &7- Принудительно начать ПВП"));
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("reload")) {
            reloadSettings();
            sender.sendMessage(Utils.color("&aКонфигурация перезагружена!"));
            getLogger().info("Config reloaded by " + sender.getName());
            return true;
        }

        if (subCommand.equals("stop")) {
            if (args.length < 2) {
                sender.sendMessage(Utils.color("&cИспользование: &e/antirelog stop <игрок|all> [время]"));
                sender.sendMessage(Utils.color("&7[время] — защита от повторного ПВП: 60, 5m, 1h"));
                return true;
            }

            String target = args[1];
            long protectionTime = args.length >= 3 ? parseTime(args[2]) : 0;

            if (target.equalsIgnoreCase("all")) {
                int stopped = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (pvpManager.isInPvP(player) || pvpManager.isInSilentPvP(player)) {
                        pvpManager.stopPvP(player);
                        if (protectionTime > 0) {
                            cooldownManager.addProtection(player, protectionTime);
                        }
                        stopped++;
                    }
                }
                sender.sendMessage(Utils.color("&aОстановлен ПВП у &e" + stopped + " &aигроков"));
                getLogger().info("PvP stopped for " + stopped + " players by " + sender.getName());
            } else {
                Player player = Bukkit.getPlayerExact(target);
                if (player == null || !player.isOnline()) {
                    sender.sendMessage(Utils.color("&cИгрок &e" + target + " &cне найден или не в сети"));
                    return true;
                }
                if (pvpManager.isInPvP(player) || pvpManager.isInSilentPvP(player)) {
                    pvpManager.stopPvP(player);
                    if (protectionTime > 0) {
                        cooldownManager.addProtection(player, protectionTime);
                    }
                    sender.sendMessage(Utils.color("&aПВП остановлен у игрока &e" + player.getName()));
                    player.sendMessage(Utils.color("&aАдминистратор остановил ваш режим ПВП"));
                    getLogger().info("PvP stopped for " + player.getName() + " by " + sender.getName());
                } else {
                    sender.sendMessage(Utils.color("&e" + player.getName() + " &cне находится в режиме ПВП"));
                }
            }
            return true;
        }

        if (subCommand.equals("give")) {
            if (args.length < 3) {
                sender.sendMessage(Utils.color("&cИспользование: &e/antirelog give <игрок1> <игрок2>"));
                return true;
            }

            Player player1 = Bukkit.getPlayerExact(args[1]);
            Player player2 = Bukkit.getPlayerExact(args[2]);

            if (player1 == null || !player1.isOnline()) {
                sender.sendMessage(Utils.color("&cИгрок &e" + args[1] + " &cне найден или не в сети"));
                return true;
            }
            if (player2 == null || !player2.isOnline()) {
                sender.sendMessage(Utils.color("&cИгрок &e" + args[2] + " &cне найден или не в сети"));
                return true;
            }
            if (player1 == player2) {
                sender.sendMessage(Utils.color("&cНельзя начать ПВП игрока с самим собой"));
                return true;
            }
            if (player1.getWorld() != player2.getWorld()) {
                sender.sendMessage(Utils.color("&cИгроки находятся в разных мирах"));
                return true;
            }

            pvpManager.forceStartPvP(player1, player2);

            sender.sendMessage(Utils.color("&aПВП начат между &e" + player1.getName() + " &aи &e" + player2.getName()));
            player1.sendMessage(Utils.color("&aАдминистратор начал режим ПВП между вами и &e" + player2.getName()));
            player2.sendMessage(Utils.color("&aАдминистратор начал режим ПВП между вами и &e" + player1.getName()));
            getLogger().info("PvP forced between " + player1.getName() + " and " + player2.getName() + " by " + sender.getName());
            return true;
        }

        sender.sendMessage(Utils.color("&cНеизвестная команда. Используйте &e/antirelog &cдля помощи"));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("antirelog.admin")) {
            return completions;
        }

        if (args.length == 1) {
            completions.add("reload");
            completions.add("stop");
            completions.add("give");
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("stop")) {
                completions.add("all");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (pvpManager.isInPvP(player) || pvpManager.isInSilentPvP(player)) {
                        completions.add(player.getName());
                    }
                }
            } else if (args[0].equalsIgnoreCase("give")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("stop")) {
                completions.add("60");
                completions.add("5m");
                completions.add("10m");
                completions.add("1h");
            } else if (args[0].equalsIgnoreCase("give")) {
                String firstPlayer = args[1];
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!player.getName().equals(firstPlayer)) {
                        completions.add(player.getName());
                    }
                }
            }
        }

        String partial = args[args.length - 1].toLowerCase();
        List<String> filtered = new ArrayList<>();
        for (String completion : completions) {
            if (completion.toLowerCase().startsWith(partial)) {
                filtered.add(completion);
            }
        }

        return filtered;
    }

    private void loadConfig() {
        fixFolder();
        settings = Configuration.builder(Settings.class)
                .file(new File(getDataFolder(), "config.yml"))
                .provider(BukkitConfigurationProvider.class).build();
        ConfigurationProvider provider = settings.getConfigurationProvider();
        provider.reloadFileFromDisk();
        File file = provider.getConfigFile();

        if (file.exists() && provider.get("config-version") == null) {
            try {
                Files.move(file.toPath(), new File(file.getParentFile(), "config.old." + System.nanoTime()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }
            provider.reloadFileFromDisk();
        }

        if (!file.exists()) {
            settings.save();
            settings.loaded();
            getLogger().info("config.yml успешно создан");
        } else if (provider.isFileSuccessfullyLoaded()) {
            if (settings.load()) {
                Object configVersionObj = provider.get("config-version");
                String configVersion = (configVersionObj != null)
                        ? String.valueOf(configVersionObj).trim()
                        : "unknown";

                if (!configVersion.equals(settings.getConfigVersion())) {
                    getLogger().info("Конфиг был обновлен. Проверьте новые значения");
                    settings.save();
                }
                getLogger().info("Конфиг успешно загружен (версия: " + configVersion + ")");
            } else {
                getLogger().warning("Не удалось загрузить конфиг");
                settings.loaded();
            }
        } else {
            getLogger().warning("Can't load settings from file, using default...");
        }
    }

    private void fixFolder() {
        File oldFolder = new File(getDataFolder().getParentFile(), "Antirelog");
        if (!oldFolder.exists()) {
            return;
        }
        try {
            File actualFolder = oldFolder.getCanonicalFile();
            if (actualFolder.getName().equals("Antirelog")) {
                File oldConfig = new File(actualFolder, "config.yml");
                if (!oldConfig.exists()) {
                    deleteFolder(actualFolder.toPath());
                    return;
                }
                List<String> oldConfigLines = Files.readAllLines(oldConfig.toPath(), StandardCharsets.UTF_8);
                String firstLine = oldConfigLines.size() > 0 ? oldConfigLines.get(0) : null;
                deleteFolder(actualFolder.toPath());
                File newFolder = getDataFolder();
                if (!newFolder.exists()) {
                    newFolder.mkdir();
                }
                File oldConfigInNewFolder = new File(newFolder, "config.yml");
                if (firstLine != null && firstLine.startsWith("config-version")) {
                    if (oldConfigInNewFolder.exists()) {
                        Files.move(oldConfigInNewFolder.toPath(), new File(oldConfigInNewFolder.getParentFile(),
                                "config.old." + System.nanoTime()).toPath());
                    }
                    Files.write(oldConfigInNewFolder.toPath(), oldConfigLines, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                    getLogger().log(Level.WARNING, "Old config.yml file from folder 'Antirelog' was moved to 'AntiRelog' folder");
                } else {
                    Files.write(new File(oldConfigInNewFolder.getParentFile(), "config.old." + System.nanoTime()).toPath(),
                            oldConfigLines, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                    getLogger().log(Level.WARNING, "Old config.yml file from folder 'Antirelog' was moved to 'AntiRelog' folder with " +
                            "different name");
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Something going wrong while renaming folder Antirelog -> AntiRelog", e);
        }
    }

    private void deleteFolder(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    public void reloadSettings() {
        settings.getConfigurationProvider().reloadFileFromDisk();
        if (settings.getConfigurationProvider().isFileSuccessfullyLoaded()) {
            settings.load();
        }
        if (pvpManager != null) {
            pvpManager.reloadWhitelistedCommands();
            pvpManager.getBossbarManager().clearBossbars();
            pvpManager.getBossbarManager().createBossBars();
        }
        getServer().getScheduler().cancelTasks(this);
        if (pvpManager != null) {
            pvpManager.onPluginDisable();
            pvpManager.onPluginEnable();
        }
        if (cooldownManager != null) {
            cooldownManager.clearAll();
        }
        getLogger().info("Settings reloaded successfully");
    }

    public boolean isProtocolLibEnabled() {
        return protocolLib;
    }

    public boolean isWorldguardEnabled() {
        return worldguard;
    }

    private void detectPlugins() {
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            WorldGuardWrapper.getInstance().registerEvents(this);
            Bukkit.getPluginManager().registerEvents(new WorldGuardListener(settings, pvpManager), this);
            worldguard = true;
        }
        try {
            Class.forName("net.ess3.api.events.teleport.PreTeleportEvent");
            Bukkit.getPluginManager().registerEvents(new EssentialsTeleportListener(pvpManager, settings), this);
        } catch (ClassNotFoundException e) {
            // EssentialsX не установлен
        }
        protocolLib = Bukkit.getPluginManager().isPluginEnabled("ProtocolLib") && VersionUtils.isVersion(9);
    }

    public Settings getSettings() {
        return settings;
    }

    public PvPManager getPvpManager() {
        return pvpManager;
    }

    public PowerUpsManager getPowerUpsManager() {
        return pvpManager.getPowerUpsManager();
    }

    public BossbarManager getBossbarManager() {
        return pvpManager.getBossbarManager();
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    private long parseTime(String timeArg) {
        try {
            timeArg = timeArg.toLowerCase();
            if (timeArg.endsWith("s")) {
                return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 1000L;
            } else if (timeArg.endsWith("m")) {
                return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 60L * 1000L;
            } else if (timeArg.endsWith("h")) {
                return Long.parseLong(timeArg.substring(0, timeArg.length() - 1)) * 60L * 60L * 1000L;
            } else {
                return Long.parseLong(timeArg) * 1000L;
            }
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return 0;
        }
    }
}