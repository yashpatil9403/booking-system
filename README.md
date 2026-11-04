# Booking System with Race Condition Protection

A small Spring Boot backend for booking time slots. The part I spent the most time on is what happens when
several people try to book the same slot at the same moment. Only one of them should get it, and the rest
should get a clear "already booked" response instead of a double booking or a random 500.

Stack: Java 17, Spring Boot 3.3.5, Spring Data JPA (Hibernate), H2, Spring Security with JWT, JUnit 5 and
Mockito, JaCoCo for coverage, SonarQube for code quality.

## Running it

You need JDK 17 and Maven installed.

```bash
git clone <https://github.com/yashpatil9403/booking-system>
cd booking-system
mvn spring-boot:run
```

It starts on port 8080. The H2 console is at `http://localhost:8080/h2-console`
(JDBC URL `jdbc:h2:mem:bookingdb`, user `sa`, no password).

An admin user is created on startup: `admin` / `admin123`. You can change it with the `ADMIN_USERNAME` and
`ADMIN_PASSWORD` environment variables. Normal users sign up through `/auth/register` and always get the
USER role, so nobody can make themselves an admin.

To run the tests and the coverage check:

```bash
mvn clean verify
```

The JaCoCo report ends up in `target/site/jacoco/index.html`. The build is set up to fail if line coverage
drops below 80%.

There is also a Postman collection with all the requests if you would rather click through the API.

## Endpoints

Everything except `/auth/**` needs an `Authorization: Bearer <token>` header. The token comes from `/auth/login`.

| Method | Path                          | Who          | What it does                              |
|--------|-------------------------------|--------------|-------------------------------------------|
| POST   | `/auth/register`              | anyone       | create a user account                     |
| POST   | `/auth/login`                 | anyone       | returns a JWT                             |
| POST   | `/slots`                      | ADMIN        | create a slot                             |
| GET    | `/slots`                      | USER, ADMIN  | list slots, optional `?status=AVAILABLE`  |
| POST   | `/bookings`                   | USER         | book a slot, body is `{"slotId": 1}`      |
| POST   | `/bookings/{id}/cancel`       | USER         | cancel your own booking                   |
| POST   | `/admin/bookings/{id}/cancel` | ADMIN        | cancel any booking                        |

Errors always come back in the same JSON shape:

```json
{ "timestamp": "...", "status": 409, "error": "Conflict", "message": "Slot 1 is already booked" }
```

For validation failures there is an extra `validationErrors` map with the field names and messages.

Status codes I used: 400 for bad input, 401 for a missing or bad token, 403 for the wrong role (or cancelling
someone else's booking), 404 when a slot or booking doesn't exist, and 409 for conflicts. A 409 covers the
slot already being booked, cancelling a booking twice, a taken username, and a lock timeout.

## How a request flows through the app

1. The `JwtAuthFilter` reads the token, loads the user and puts the role into the security context.
2. `SecurityConfig` decides if that role may call the URL. No token gives 401, the wrong role gives 403.
3. The controller validates the body with `@Valid` and hands it to a service.
4. The service runs the business logic inside a transaction and talks to the repositories.
5. If anything throws, `GlobalExceptionHandler` turns it into the JSON error above.

## Locking strategy

I went with pessimistic locking. `SlotRepository.findByIdForUpdate` uses
`@Lock(LockModeType.PESSIMISTIC_WRITE)`, which makes Hibernate run `SELECT ... FOR UPDATE` on the slot row.

My reasoning: with optimistic locking, if 20 people go for one slot, all of them read it as available and
then 19 fail at commit time and need a retry. With a pessimistic lock they simply wait in line at the
database. By the time a waiting request gets the row, the slot is already `BOOKED`, so it fails right away
with a clean 409 and there is no retry logic to write.

`Slot` also has a `@Version` field as a backup. If a version conflict or lock timeout ever does happen, the
exception handler returns a 409 for it too.

There are no `synchronized` blocks or in-memory locks anywhere. The lock is held by the database, so it
doesn't matter how many app instances are running, and a restart can't leave anything stuck, because a row
lock only exists while a transaction is open.

## Transaction boundaries

The `@Transactional` methods are in `BookingService`, not in the controllers.

Booking a slot is one transaction:
lock the slot row, check it is `AVAILABLE`, set it to `BOOKED`, insert the booking as `ACTIVE`, commit.

Cancelling is also one transaction:
lock the booking row, check the caller is allowed to cancel it, check it isn't already cancelled, lock the
slot row, set the booking to `CANCELLED` and the slot back to `AVAILABLE`, commit.

If any step throws, everything rolls back. That is how the slot status and the booking record stay in sync,
and why there can't be a half-finished update. Putting the boundary in the service means the lock is only
held while the actual business work runs.

Cancel locks booking first and then slot, and booking a slot only ever locks the slot. Keeping the order the
same everywhere means the two can't deadlock each other.

## How the race condition is prevented

Say users A and B both book slot 1 at the same time:

```
A: BEGIN                                    B: BEGIN
A: SELECT slot 1 FOR UPDATE (gets lock)     B: SELECT slot 1 FOR UPDATE (waits)
A: status is AVAILABLE
A: slot -> BOOKED, insert booking
A: COMMIT                                   B: gets the lock, reads slot 1 -> BOOKED
A: gets 201                                 B: SlotAlreadyBookedException, rollback
                                            B: gets 409
```

The availability check and the update both happen while holding the lock, so nobody else can sneak in
between them.

## Example concurrent booking scenario

The main proof is `BookingConcurrencyTest`. It creates one slot and 20 users, starts 20 threads, releases them
all at the same moment with a `CountDownLatch`, and each thread calls `bookSlot` for the same slot. It then
checks that exactly 1 call succeeded, the other 19 got `SlotAlreadyBookedException`, there is exactly one
ACTIVE booking in the database, and the slot is `BOOKED`.

The same class also fires 10 simultaneous cancels at one booking (only one should win) and checks that a slot
can be booked again after it was cancelled.

```bash
mvn test -Dtest=BookingConcurrencyTest
```

You can also see the same thing through the API. Create a slot, then send two booking requests for it from
two different users. The first gets a `201` and the second gets a `409` with "Slot 1 is already booked".

## SonarQube

The Sonar plugin and the JaCoCo report path are already set in `pom.xml`. To run it locally:

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
```

Open `http://localhost:9000`, log in with admin/admin, create a project with the key `booking-system` and
generate a token. Then:

```bash
mvn clean verify sonar:sonar -Dsonar.token=<your-token>
```

## Deployment

Not deployed. Deployment was optional, so I only ran it locally.

## Assumptions and design decisions

- The database is in-memory H2 as the assignment asks, so data is lost on restart. Correctness doesn't depend
  on that though, since it comes from the database locks and not from anything in app memory. If you want data
  to survive restarts, change the URL to `jdbc:h2:file:./data/bookingdb`.
- H2 doesn't support partial unique indexes, so I couldn't add a constraint for "one ACTIVE booking per slot".
  The row lock plus the status check (and `@Version`) is what guarantees it.
- Only users can book, as the spec says, so an admin calling `POST /bookings` gets a 403.
- A slot's start time has to be in the future and the end time after the start. I didn't add overlap checks
  between slots since the spec doesn't ask for them.
- Cancelling a booking sets the slot back to `AVAILABLE`, so it can be booked again.
- Trying to cancel someone else's booking returns 403 rather than 404, going by the "user can cancel only
  their own booking" rule.
- The JWT secret and admin password in `application.yml` are only dev defaults. Use environment variables for
  anything real.

## Project layout

```
src/main/java/com/example/booking
  config/       DataInitializer, creates the admin user
  controller/   Auth, Slot, Booking and AdminBooking controllers
  dto/          request/response records and ErrorResponse
  entity/       Slot, Booking, User and the enums
  exception/    custom exceptions and GlobalExceptionHandler
  repository/   Spring Data repositories (the FOR UPDATE queries are here)
  security/     JwtService, JwtAuthFilter, SecurityConfig, 401/403 handler
  service/      AuthService, SlotService, BookingService
src/test/java/...   unit tests with Mockito, API tests with MockMvc, the concurrency test
```
