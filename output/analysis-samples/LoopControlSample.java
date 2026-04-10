public class LoopControlSample {

    public int sumPositiveUntilLimit(int[] values, int limit) {
        int sum = 0;

        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0) {
                continue;
            }

            sum += values[i];
            if (sum >= limit) {
                break;
            }
        }

        return sum;
    }
}
