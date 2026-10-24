FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY ExamSeatingSystem.java students.csv halls.csv ./
RUN javac ExamSeatingSystem.java
CMD ["java", "ExamSeatingSystem"]
