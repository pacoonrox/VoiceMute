package me.pacotaco.laby;

import net.labymod.serverapi.server.bukkit.LabyModPlayer;
import net.labymod.serverapi.server.bukkit.LabyModProtocolService;
import net.labymod.serverapi.integration.voicechat.VoiceChatPlayer;
import net.labymod.serverapi.integration.voicechat.model.VoiceChatMute;
import org.bukkit.entity.Player;

public class LabyVoiceManager {

    public void mute(Player player, String reason, long expiry) {
        LabyModPlayer lp = LabyModProtocolService.get().getPlayer(player.getUniqueId());
        if (lp != null) {
            VoiceChatPlayer vp = lp.getIntegrationPlayer(VoiceChatPlayer.class);
            if (vp != null) vp.mute(VoiceChatMute.create(player.getUniqueId(), reason, expiry));
        }
    }

    public void unmute(Player player) {
        LabyModPlayer lp = LabyModProtocolService.get().getPlayer(player.getUniqueId());
        if (lp != null) {
            VoiceChatPlayer vp = lp.getIntegrationPlayer(VoiceChatPlayer.class);
            if (vp != null) vp.unmute();
        }
    }
}
