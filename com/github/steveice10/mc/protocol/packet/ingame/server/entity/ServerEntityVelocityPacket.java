package com.github.steveice10.mc.protocol.packet.ingame.server.entity;

import com.github.steveice10.packetlib.io.NetInput;
import com.github.steveice10.packetlib.io.NetOutput;
import com.github.steveice10.packetlib.packet.Packet;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;

import java.io.IOException;

@Data
@With
@Setter(AccessLevel.NONE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
public class ServerEntityVelocityPacket implements Packet {
    private int entityId;
    private double motionX;
    private double motionY;
    private double motionZ;

    @Override
    public void read(NetInput in) throws IOException {
        this.entityId = in.readVarInt();
        this.motionX = in.readShort() / 80D;
        this.motionY = in.readShort() / 80D;
        this.motionZ = in.readShort() / 80D;
    }

    @Override
    public void write(NetOutput out) throws IOException {
        out.writeVarInt(this.entityId);
        out.writeShort((int) (this.motionX * 80));
        out.writeShort((int) (this.motionY * 80));
        out.writeShort((int) (this.motionZ * 80));
    }

    @Override
    public boolean isPriority() {
        return false;
    }
}
