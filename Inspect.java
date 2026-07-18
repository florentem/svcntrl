public class Inspect {
    public static void main(String[] args) throws Exception {
        for(java.lang.reflect.Method m : net.minecraft.GameVersion.class.getMethods()) {
            System.out.println(m.getName());
        }
    }
}
