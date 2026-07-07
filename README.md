# Netherlands Map Quiz
This application is a geography quiz tool designed to help you learn the locations of populated places in the Netherlands. Inspired by tools like Seterra and GeoGuessr, it provides an interactive experience for mapping and identifying Dutch towns and cities.

# Getting Started
### Prerequisites
- **Java 21+** (or the latest JDK version)
- **IntelliJ IDEA** (Recommended for managing Gradle and Run Configurations)
- **Gradle**

### Project Structure
- `backend/`: Contains the Ktor server and the `Geocoder` utility.
- `backend/data/`: Stores the generated `places.json` and `review.csv` files.
- `frontend/`: Contains the static `HTML/JS` assets.

# Run Configurations
To run the application, set up the following configurations in IntelliJ IDEA.

### 1. Run Geocoder
Used to generate/update the `places.json` file from your CSV input.
- **Name**: `Run Geocoder`
- **Main Class**: `backend.GeocoderKt`
- **Program Arguments**: `backend/src/main/resources/places.csv backend/data/places.json backend/data/review.csv`
- **Working Directory**: [Project Root]

### 2. Run Server
Used to start the **Ktor** backend to serve the application.
- **Name**: Run Server
- **Main Class**: backend.ApplicationKt
- **Environment Variables**: PLACES_JSON_PATH=backend/data/places.json
- **VM Options**: ```
--add-opens java.base/sun.misc=ALL-UNNAMED
--add-opens java.base/java.nio=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED```
- Working Directory: [Project Root]

# Adding Entries
1. Navigate to `backend/src/main/resources/places.csv`.
2. Add a new line following the existing format: `Name`.
   - Example: `Utrecht`
   - Example: `Lelystad`
3. If you are adding a place that shares a name with another (e.g., Achttienhoven), ensure you include the disambiguator in parentheses to help the Geocoder:
   - `Name (Municipality)`
   - Example: `Achttienhoven (Utrecht)`

# How to Play
1. **Geocoding**: Run the `Geocoder` to ensure your `data/places.json` is up to date.
2. **Launch Server**: Run the Server configuration.
3. **Access**: Open your browser and navigate to http://localhost:8080.
4. **Modes**:
   - **Click Mode**: The app displays a place name; you must click the correct location on the interactive map.
   - **Name Mode**: The app shows a marker on the map; you must type the name of the place.
   - **Province Mode**: The app displays a place name; you must click the correct province on the map within three tries.
# Technical Overview
The backend uses **Ktor** with **Netty**, leveraging **Jackson** for **JSON serialization**. **Geocoding** is performed via the **Nominatim (OpenStreetMap)** API, with built-in throtlling and confidence heuristics to ensure data quality.

# Quality Assurance
If the **Geocoder** flags any entries as low confidence or missing, please review the `backend/data/review.csv` file. Manually verify the locations and update the **input CSV or JSON** as needed to ensure your quiz remains accurate.

