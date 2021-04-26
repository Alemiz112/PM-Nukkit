package cn.nukkit.network;

import cn.nukkit.Player;
import cn.nukkit.network.protocol.BatchPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.UpdateBlockPacket;
import cn.nukkit.utils.Binary;
import cn.nukkit.utils.BinaryStream;
import cn.nukkit.utils.Zlib;
import com.nukkitx.network.raknet.EncapsulatedPacket;
import com.nukkitx.network.raknet.RakNetServerSession;
import com.nukkitx.network.raknet.RakNetSessionListener;
import com.nukkitx.network.raknet.RakNetState;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.network.util.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.PlatformDependent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.message.FormattedMessage;

import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

@Log4j2
public class NukkitRakNetSession implements RakNetSessionListener {
    private final RakNetInterface rakInterface;
    private final RakNetServerSession session;
    private final ScheduledFuture<?> tickFuture;

    private final Queue<DataPacket> inbound = PlatformDependent.newSpscQueue();
    private final Queue<DataPacket> outbound = PlatformDependent.newMpscQueue();

    private Player player;
    private String disconnectReason;
    private int compressionLevel;

    public NukkitRakNetSession(RakNetInterface rakInterface, RakNetServerSession session) {
        this.rakInterface = rakInterface;
        this.session = session;
        this.compressionLevel = rakInterface.getNetwork().getServer().networkCompressionLevel;
        this.tickFuture = session.getEventLoop().scheduleAtFixedRate(this::processOutbound, 50, 50, TimeUnit.MILLISECONDS);
    }

    private void disconnect(String reason) {
        this.disconnectReason = reason;
        this.tickFuture.cancel(false);
    }

    public void sendPacket(DataPacket packet) {
        DataPacket dataPacket = packet.clone();
        dataPacket.protocol = this.player.protocol;
        dataPacket.tryEncode();
        this.outbound.offer(dataPacket);
    }

    public void processOutbound() {
        try {
            List<DataPacket> toBatch = new ObjectArrayList<>();
            DataPacket packet;
            while ((packet = this.outbound.poll()) != null) {
                if (!(packet instanceof BatchPacket)) {
                    toBatch.add(packet);
                    continue;
                }

                if (!toBatch.isEmpty()) {
                    this.sendPackets(toBatch);
                    toBatch.clear();
                }
                this.sendBatched(((BatchPacket) packet).payload);
            }

            if (!toBatch.isEmpty()) {
                this.sendPackets(toBatch);
            }
        } catch (Exception e) {
            log.error("Failed to send outbound packets!", e);
        }
    }

    public void processInbound() {
        DataPacket packet;
        while ((packet = this.inbound.poll()) != null) {
            try {
                this.player.handleDataPacket(packet);
            } catch (Exception e) {
                log.error(new FormattedMessage("An error occurred whilst handling {} for {}",
                        new Object[]{packet.getClass().getSimpleName(), this.player.getName()}, e));
            }
        }
    }

    protected void onPlayerCreation(Player player) {
        player.raknetProtocol = this.session.getProtocolVersion();
        this.player = player;
    }

    @Override
    public void onSessionChangeState(RakNetState rakNetState) {
    }

    @Override
    public void onDirect(ByteBuf byteBuf) {
        // We don't allow any direct packets so ignore
    }

    @Override
    public void onDisconnect(DisconnectReason disconnectReason) {
        if (disconnectReason == DisconnectReason.TIMED_OUT) {
            this.disconnect("Timed out");
        } else {
            this.disconnect("Disconnected from Server");
        }
    }

    @Override
    public void onEncapsulated(EncapsulatedPacket packet) {
        ByteBuf buffer = packet.getBuffer();
        short packetId = buffer.readUnsignedByte();
        if (packetId != 0xfe) {
            return;
        }

        byte[] packetBuffer = new byte[buffer.readableBytes()];
        buffer.readBytes(packetBuffer);

        if (this.player == null) {
            BatchPacket batchPacket = new BatchPacket();
            batchPacket.setBuffer(packetBuffer);
            batchPacket.decode();
            this.inbound.offer(batchPacket);
            return;
        }

        try {
            this.rakInterface.getNetwork().processBatch(packetBuffer,
                    this.inbound,
                    this.session.getProtocolVersion(),
                    this.player.protocol
            );
        } catch (ProtocolException e) {
            this.disconnect("Sent malformed packet");
            log.error("Unable to process batch packet", e);
        }
    }

    private void sendPackets(Collection<DataPacket> packets) {
        BinaryStream batched = new BinaryStream();
        for (DataPacket packet : packets) {
            Preconditions.checkState(packet.isEncoded, "Packet should have already been encoded");
            byte[] buf = packet.getBuffer();
            batched.putUnsignedVarInt(buf.length);
            batched.put(buf);
        }

        try {
            if (this.session.getProtocolVersion() >= 10) {
                this.sendBatched(Zlib.deflateRaw(batched.getBuffer(), this.compressionLevel));
            } else {
                this.sendBatched(Zlib.deflate(batched.getBuffer(), this.compressionLevel));
            }
        } catch (Exception e) {
            log.error("Unable to compress batched packets", e);
        }
    }

    private void sendBatched(byte[] payload) {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.ioBuffer(1 + payload.length);
        byteBuf.writeByte(0xfe);
        byteBuf.writeBytes(payload);
        this.session.send(byteBuf);
    }

    public Player getPlayer() {
        return this.player;
    }

    public String getDisconnectReason() {
        return this.disconnectReason;
    }

    public InetSocketAddress getAddress() {
        return this.session.getAddress();
    }

    public int getCompressionLevel() {
        return this.compressionLevel;
    }

    public void setCompressionLevel(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }
}
