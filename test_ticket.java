import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.ChunkPos;
import java.util.Comparator;
public class Test {
    public static final ChunkTicketType<ChunkPos> ASYNC_LOAD = ChunkTicketType.create("svcntrl_load", Comparator.comparingLong(ChunkPos::toLong), 20);
}
