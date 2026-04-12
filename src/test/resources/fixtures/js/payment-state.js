function mapPaymentState(payment) {
    switch (payment.status) {
        case "pending":
            return "queue";
        case "paid":
            return payment.refundRequested ? "review" : "complete";
        case "failed":
            return "retry";
        default:
            return "unknown";
    }
}
