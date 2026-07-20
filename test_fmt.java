public class test_fmt {
    public static void main(String[] args) {
        int maxVol = 5000000;
        long volume = 80443475L;
        System.out.println(String.format("Project area too large! Maximum volume is %s blocks, but you selected %s blocks.", maxVol, volume));
    }
}
