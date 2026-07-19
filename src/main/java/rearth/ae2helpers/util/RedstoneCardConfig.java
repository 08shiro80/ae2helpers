package rearth.ae2helpers.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public record RedstoneCardConfig(boolean strongSignal, RedstoneMode mode, @Nullable Direction side, int pulseLength) {

    public static final int DEFAULT_PULSE_LENGTH = 4;

    public static final RedstoneCardConfig DEFAULT = new RedstoneCardConfig(false, RedstoneMode.ACTIVE, (Direction) null, DEFAULT_PULSE_LENGTH);

    public static final Codec<RedstoneCardConfig> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.BOOL.fieldOf("strong_signal").forGetter(RedstoneCardConfig::strongSignal),
      RedstoneMode.CODEC.fieldOf("mode").forGetter(RedstoneCardConfig::mode),
      Direction.CODEC.optionalFieldOf("side").forGetter(c -> Optional.ofNullable(c.side())),
      Codec.INT.optionalFieldOf("pulse_length", DEFAULT_PULSE_LENGTH).forGetter(RedstoneCardConfig::pulseLength)
    ).apply(inst, (strong, mode, side, pulse) -> new RedstoneCardConfig(strong, mode, side.orElse(null), pulse)));

    public static final StreamCodec<RegistryFriendlyByteBuf, RedstoneCardConfig> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.BOOL, RedstoneCardConfig::strongSignal,
      RedstoneMode.STREAM_CODEC, RedstoneCardConfig::mode,
      Direction.STREAM_CODEC.apply(ByteBufCodecs::optional), c -> Optional.ofNullable(c.side()),
      ByteBufCodecs.VAR_INT, RedstoneCardConfig::pulseLength,
      RedstoneCardConfig::new
    );

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private RedstoneCardConfig(boolean strongSignal, RedstoneMode mode, Optional<Direction> side, int pulseLength) {
        this(strongSignal, mode, side.orElse(null), pulseLength);
    }
}
