public class SentinelSearch {

    public int findFirst(int[] values, int target) {
        int index = 0;

        while (index < values.length) {
            if (values[index] == -999) {
                break;
            }

            if (values[index] == target) {
                return index;
            }

            index++;
        }

        return -1;
    }
}
