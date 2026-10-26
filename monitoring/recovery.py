"""Conservative liveness rules for the four-day dev collection trial."""

from datetime import datetime, time, timedelta


STARTUP_GRACE = timedelta(seconds=45)


def parsed(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def issue(kind, restart=False, **detail):
    return {"kind": kind, "restart": restart, "detail": detail}


def heartbeat_issues(service, health, pid, moment, launched_at):
    if moment - launched_at < STARTUP_GRACE:
        return []
    if not health:
        return [issue("HEARTBEAT_MISSING", True)]
    if health.get("pid") != pid:
        return [issue("HEARTBEAT_WRONG_PID", True)]
    heartbeat = parsed(health.get("heartbeatAt"))
    if heartbeat is None or moment - heartbeat > timedelta(seconds=25 if service == "market" else 30):
        return [issue("HEARTBEAT_STALE", True)]
    return []


def market_issues(health, pid, moment, launched_at, sessions):
    problems = heartbeat_issues("market", health, pid, moment, launched_at)
    if problems or moment - launched_at < STARTUP_GRACE:
        return problems
    active = []
    for venue, _name, start, end in sessions:
        begins = datetime.combine(moment.date(), time.fromisoformat(start), moment.tzinfo)
        ends = datetime.combine(moment.date(), time.fromisoformat(end), moment.tzinfo)
        if begins <= moment < ends:
            active.append((venue, begins))
    if not active:
        return []
    subscriptions = health.get("subscriptions") or []
    if not subscriptions:
        return [issue("WATCHLIST_EMPTY")]
    recent_confirmation = max((parsed(row.get("subscriptionConfirmedAt")) for row in subscriptions
                               if parsed(row.get("subscriptionConfirmedAt"))), default=None)
    for venue, begins in active:
        rows = [row for row in subscriptions if row.get("venue") == venue]
        if not rows:
            continue
        if any(row.get("subscriptionState") != "SUBSCRIBED" and
               moment - max(filter(None, (launched_at, begins, parsed(row.get("updatedAt"))))) > timedelta(seconds=90)
               for row in rows):
            problems.append(issue("SUBSCRIPTION_STALLED", True, venue=venue))
        if any(row.get("subscriptionState") != "SUBSCRIBED" for row in rows):
            continue
        received = max((parsed(row.get("lastReceivedAt")) for row in rows
                        if parsed(row.get("lastReceivedAt"))), default=None)
        since = max(filter(None, (received, recent_confirmation, launched_at, begins)))
        quiet = moment - since
        if venue == "KRX" and quiet > timedelta(seconds=180):
            problems.append(issue("TICK_STALE", quiet > timedelta(seconds=300), venue=venue,
                                  seconds=int(quiet.total_seconds())))
        elif venue == "NXT" and quiet > timedelta(minutes=10):
            problems.append(issue("TICK_STALE", False, venue=venue,
                                  seconds=int(quiet.total_seconds())))
    last_bar = parsed(health.get("lastBarAt"))
    first_active = min(start for _, start in active)
    since_bar = max(filter(None, (last_bar, recent_confirmation, launched_at, first_active)))
    if moment - since_bar > timedelta(minutes=3):
        problems.append(issue("BAR_STALE", True, seconds=int((moment - since_bar).total_seconds())))
    return problems


def prediction_issues(health, output_at, moment, launched_at):
    if moment - launched_at < STARTUP_GRACE or not health:
        return []
    bar = parsed(health.get("lastBarAt"))
    if bar is None or moment - bar > timedelta(minutes=3):
        return []
    output = output_at if output_at and output_at.tzinfo else None
    first_bar = parsed(health.get("firstBarAt")) or bar
    reference = output or first_bar
    if bar - reference > timedelta(minutes=2) and moment - reference > timedelta(minutes=3):
        return [issue("PREDICTION_STALE", True, seconds=int((moment - reference).total_seconds()))]
    return []
