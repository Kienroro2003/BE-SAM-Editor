function exportReport(rows, writer) {
    try {
        for (const row of rows) {
            if (!row.visible) {
                continue;
            }

            writer.write(row.value);
        }
    } catch (error) {
        return {
            status: "failed",
            message: error.message,
        };
    } finally {
        writer.close();
    }

    return {
        status: "done",
        count: rows.length,
    };
}
