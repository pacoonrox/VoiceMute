package me.pacotaco.laby;

import net.labymod.serverapi.server.bukkit.LabyModPlayer;
import net.labymod.serverapi.server.bukkit.LabyModProtocolService;
import net.labymod.serverapi.integration.voicechat.VoiceChatPlayer;
import net.labymod.serverapi.integration.voicechat.model.VoiceChatMute;
import org.bukkit.entity.Player;

public class LabyVoiceManager {

    /** Returns true if the LabyMod API mute was applied, false if the player is not on LabyMod/voice chat. */
    public boolean mute(Player player, String reason, long expiry) {
        LabyModProtocolService service = LabyModProtocolService.get();
        if (service == null) return false;
        LabyModPlayer lp = service.getPlayer(player.getUniqueId());
        if (lp == null) return false;
        VoiceChatPlayer vp = lp.getIntegrationPlayer(VoiceChatPlayer.class);
        if (vp == null) return false;
        vp.mute(VoiceChatMute.create(player.getUniqueId(), reason, expiry));
        return true;
    }

    public void unmute(Player player) {
        LabyModProtocolService service = LabyModProtocolService.get();
        if (service == null) return;
        LabyModPlayer lp = service.getPlayer(player.getUniqueId());
        if (lp != null) {
            VoiceChatPlayer vp = lp.getIntegrationPlayer(VoiceChatPlayer.class);
            if (vp != null) vp.unmute();
        }
    }
}
