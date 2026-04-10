public class GuardClauseCalculator {

    public int divide(int dividend, int divisor) {
        if (divisor == 0) {
            return -1;
        }

        if (dividend < 0) {
            return 0;
        }

        return dividend / divisor;
    }
}
