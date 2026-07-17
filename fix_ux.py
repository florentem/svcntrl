with open('src/main/java/com/svcntrl/core/UXManager.java', 'r') as f:
    c = f.read()
    
c = c.replace('player.getBlockPos().getSquaredDistance(net.minecraft.util.math.BlockPos.ofFloored(project.getCenter())) > 16384',
              'player.getBlockPos().getSquaredDistance(new net.minecraft.util.math.BlockPos((project.getMin().getX() + project.getMax().getX()) / 2, (project.getMin().getY() + project.getMax().getY()) / 2, (project.getMin().getZ() + project.getMax().getZ()) / 2)) > 16384')

with open('src/main/java/com/svcntrl/core/UXManager.java', 'w') as f:
    f.write(c)
