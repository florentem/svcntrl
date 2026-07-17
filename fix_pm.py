import re

with open('src/main/java/com/svcntrl/core/PreviewManager.java', 'r') as f:
    content = f.read()

# Fix getString
content = content.replace('String idStr = entityNbt.getString("id");', 'String idStr = entityNbt.getString("id").orElse("");')
# Wait, let's just make it simpler. I'll use regex to fix all the issues
content = content.replace('.getOrEmpty(', '.getOptional(')
content = content.replace('entityNbt.contains("Rotation", 9)', 'entityNbt.contains("Rotation")')
content = content.replace('entityNbt.getList("Rotation", 5)', 'entityNbt.getList("Rotation")')
content = content.replace('entityNbt.contains("Motion", 9)', 'entityNbt.contains("Motion")')
content = content.replace('entityNbt.getList("Motion", 6)', 'entityNbt.getList("Motion")')
content = content.replace('yaw = rotation.getFloat(0);', 'yaw = rotation.getFloat(0).orElse(0f);')
content = content.replace('pitch = rotation.getFloat(1);', 'pitch = rotation.getFloat(1).orElse(0f);')
content = content.replace('vel = new net.minecraft.util.math.Vec3d(motion.getDouble(0), motion.getDouble(1), motion.getDouble(2));', 'vel = new net.minecraft.util.math.Vec3d(motion.getDouble(0).orElse(0.0), motion.getDouble(1).orElse(0.0), motion.getDouble(2).orElse(0.0));')

with open('src/main/java/com/svcntrl/core/PreviewManager.java', 'w') as f:
    f.write(content)

with open('src/main/java/com/svcntrl/mixin/ServerPlayNetworkHandlerMixin.java', 'r') as f:
    mixin = f.read()

mixin = mixin.replace('@Mixin(ServerPlayNetworkHandler.class)', '@Mixin(net.minecraft.server.network.ServerCommonNetworkHandler.class)')
mixin = mixin.replace('ServerPlayNetworkHandler playHandler = (ServerPlayNetworkHandler) (Object) this;', 'net.minecraft.server.network.ServerCommonNetworkHandler handler = (net.minecraft.server.network.ServerCommonNetworkHandler) (Object) this;\n        if (!(handler instanceof ServerPlayNetworkHandler playHandler)) return;')

with open('src/main/java/com/svcntrl/mixin/ServerPlayNetworkHandlerMixin.java', 'w') as f:
    f.write(mixin)

