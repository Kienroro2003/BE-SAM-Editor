function processOrderBatch(orders) {
    const processed = [];

    for (const order of orders) {
        if (!order) {
            continue;
        }

        if (order.cancelled) {
            break;
        }

        processed.push(order.id);
    }

    return processed;
}
