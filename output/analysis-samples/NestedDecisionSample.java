public class NestedDecisionSample {

    public int evaluate(int[] numbers, int minimum) {
        int count = 0;

        for (int i = 0; i < numbers.length; i++) {
            if (numbers[i] >= minimum) {
                if (numbers[i] % 2 == 0) {
                    count++;
                } else {
                    count += 2;
                }
            }
        }

        if (count > 3) {
            return count;
        }

        return -1;
    }
}
