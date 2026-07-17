import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.ChunkPos;

public class TestTicket {
    public static void test(ServerChunkManager mgr, ChunkPos pos) {
        mgr.addTicket(ChunkTicketType.UNKNOWN, pos, 2, pos);
    }
}
