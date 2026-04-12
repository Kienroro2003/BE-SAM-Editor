function resolveUserAccess(user, flags) {
    if (!user || !user.active) {
        return "blocked";
    }

    if (flags.includes("admin")) {
        return "admin";
    } else if (user.role === "manager") {
        return "manager";
    }

    return user.points > 100 ? "priority" : "standard";
}
