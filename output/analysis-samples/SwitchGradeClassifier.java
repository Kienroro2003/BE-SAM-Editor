public class SwitchGradeClassifier {

    public String classify(int score) {
        switch (score / 10) {
            case 10:
            case 9:
                return "excellent";
            case 8:
                return "good";
            case 7:
                return "average";
            default:
                return "retry";
        }
    }
}
