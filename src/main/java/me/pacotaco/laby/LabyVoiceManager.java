package me.pacotaco.laby;

import net.labymod.serverapi.server.bukkit.LabyModPlayer;
import net.labymod.serverapi.server.bukkit.LabyModProtocolService;
import net.labymod.serverapi.integration.voicechat.VoiceChatPlayer;
import net.labymod.serverapi.integration.voicechat.model.VoiceChatMute;
import org.bukkit.entity.Player;

public class LabyVoiceManager {

    private final LabyMutePlugin plugin;

    public LabyVoiceManager(LabyMutePlugin plugin) {
        this.plugin = plugin;
    }

    /** Returns true if the LabyMod API mute was applied, false if the player is not on LabyMod/voice chat. */
    public boolean mute(Player player, String reason, long expiry) {
        LabyModProtocolService service = LabyModProtocolService.get();
        if (service == null) return false;
        LabyModPlayer lp = service.getPlayer(player.getUniqueId());
        if (lp == null) return false;
        VoiceChatPlayer vp = lp.getIntegrationPlayer(VoiceChatPlayer.class);
        if (vp == null) return false;
        try {
            vp.mute(VoiceChatMute.create(player.getUniqueId(), reason, expiry));
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("LabyMod API mute failed for " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }

    public void unmute(Player player) {
        LabyModProtocolService service = LabyModProtocolService.get();
        if (service == null) return;
        LabyModPlayer lp = service.getPlayer(player.getUniqueId());
        if (lp == null) return;
        VoiceChatPlayer vp = lp.getIntegrationPlayer(VoiceChatPlayer.class);
        if (vp == null) return;
        try {
            vp.unmute();
        } catch (Exception e) {
            plugin.getLogger().warning("LabyMod API unmute failed for " + player.getName() + ": " + e.getMessage());
        }
    }
}
