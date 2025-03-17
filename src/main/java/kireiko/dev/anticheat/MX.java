package kireiko.dev.anticheat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import kireiko.dev.anticheat.api.PlayerContainer;
import kireiko.dev.anticheat.api.commands.CommandCompleter;
import kireiko.dev.anticheat.api.commands.CommandHandler;
import kireiko.dev.anticheat.api.player.PlayerProfile;
import kireiko.dev.anticheat.listeners.*;
import kireiko.dev.anticheat.utils.AnimatedPunishUtil;
import kireiko.dev.anticheat.utils.FunThingsUtil;
import kireiko.dev.anticheat.utils.FunnyPackets;
import kireiko.dev.millennium.types.EvictingList;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MX extends JavaPlugin {

    public static final String
            command = "mx",
            name = "MX",
            permissionHead = "mx.",
            permission = permissionHead + "admin";

    @Getter
    private static MX instance;

    public static int bannedPerMinuteCount = 0;
    public static List<Integer> bannedPerMinuteList = new EvictingList<>(60);

    public static int blockedPerMinuteCount = 0;
    public static List<Integer> blockedPerMinuteList = new EvictingList<>(60);


    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        
        // Add default config value if it doesn't exist
        if (!getConfig().contains("checkMobs")) {
            getConfig().set("checkMobs", false);
            saveConfig();
        }
        
        loadListeners();
        punishTimer();
        this.getCommand(command).setExecutor(new CommandHandler());
        this.getCommand(command).setTabCompleter(new CommandCompleter());
        getLogger().info("Launched!");
    }

    private void punishTimer() {
        AnimatedPunishUtil.init();
        FunThingsUtil.init();
        //CrasherShieldNewListener.watchdog();

        // reset vl
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            float r = (float) this.getConfig().getDouble("vlReset");
            bannedPerMinuteList.add(bannedPerMinuteCount);
            bannedPerMinuteCount = 0;
            blockedPerMinuteList.add(blockedPerMinuteCount);
            blockedPerMinuteCount = 0;
            for (PlayerProfile profile : PlayerContainer.getUuidPlayerProfileMap().values()) {
                profile.fade(r);
                profile.setFlagCount(0);
            }
        }, 20L, 1200L);

        // horrow
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (PlayerProfile profile : PlayerContainer.getUuidPlayerProfileMap().values()) {
                if (profile.horrowStage > 0) {
                    profile.horrowStage++;
                    if (profile.horrowStage > 4) {
                        FunnyPackets.closeMinecraft(profile.getPlayer());
                        profile.horrowStage = 0;
                    }
                }
            }
        }, 20L, 160L);
    }

    private void loadListeners() {
        //Bukkit.getPluginManager().registerEvents(new GhostBlockTest(), this);
        Bukkit.getPluginManager().registerEvents(new DisconnectListener(), this);
        Bukkit.getPluginManager().registerEvents(new InteractSpellListener(), this);
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new RawMovementListener());
        protocolManager.addPacketListener(new UseEntityListener());
        protocolManager.addPacketListener(new JoinListener());
        protocolManager.addPacketListener(new EntityActionListener());
        { // omni listener
            final Set<PacketType> listeners = new HashSet<>();
            for (PacketType packetType : PacketType.Play.Client.getInstance()) {
                if (packetType.isSupported()) listeners.add(packetType);
            }
            protocolManager.addPacketListener(new OmniPacketListener(listeners));
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

}
