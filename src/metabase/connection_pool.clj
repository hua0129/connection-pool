(ns metabase.connection-pool
  "Low-level logic for creating connection pools for a JDBC-based database. Used by both application DB and connected
  data warehouse DBs.

  The aim here is to completely encapsulate the connection pool library we use -- that way we can swap it out if we
  want to at some point without having to touch any other files. (TODO - this is currently true of everything except
  for the options, which are c3p0-specific -- consider abstracting those as well?)"
  (:import com.mchange.v2.c3p0.DataSources
           [java.sql Driver DriverManager]
           java.util.Properties
           javax.sql.DataSource))

;;; ------------------------------------------------ Proxy DataSource ------------------------------------------------

(defn- set-username-and-password! [^Properties properties username password]
  (let [properties (or properties (Properties.))]
    (doseq [[k v] {"user" username, "password" password}]
      (if (some? v)
        (.setProperty properties k (name v))
        (.remove properties k)))
    properties))

(defn- proxy-data-source
  "Normal c3p0 DataSource classes do not properly work with our JDBC proxy drivers for whatever reason. Use our own
  instead, which works nicely."
  (^DataSource [^String jdbc-url, ^Properties properties]
   (reify DataSource
     (getConnection [_]
       (DriverManager/getConnection jdbc-url properties))

     (getConnection [_ username password]
       (DriverManager/getConnection jdbc-url (set-username-and-password! properties username password)))))

  (^DataSource [^Driver driver, ^String jdbc-url, ^Properties properties]
   (reify DataSource
     (getConnection [_]
       (.connect driver jdbc-url properties))

     (getConnection [_ username password]
       (.connect driver jdbc-url (set-username-and-password! properties username password))))))


;;; ------------------------------------------- Creating Connection Pools --------------------------------------------

(defn map->properties
  "Create a `Properties` object from a JDBC connection spec map. Properties objects are maps of String -> String, so all
  keys and values are converted to Strings appropriately."
  ^Properties [m]
  (let [properties (Properties.)]
    (doseq [[k v] m]
      (.setProperty properties (name k) (if (keyword? v)
                                          (name v)
                                          (str v))))
    properties))

(defn- spec->properties ^Properties [spec]
  (map->properties (dissoc spec :classname :subprotocol :subname)))

(defn- unpooled-data-source
  (^DataSource [{:keys [subname subprotocol], :as spec}]
   (proxy-data-source (format "jdbc:%s:%s" subprotocol subname) (spec->properties spec)))

  (^DataSource [driver {:keys [subname subprotocol], :as spec}]
   (proxy-data-source driver (format "jdbc:%s:%s" subprotocol subname) (spec->properties spec))))

(defn pooled-data-source
  "Create a new pooled DataSource from a `clojure.java.jdbc` spec."
  (^DataSource [spec]
   (DataSources/pooledDataSource (unpooled-data-source spec)))

  (^DataSource [spec pool-properties-map]
   (DataSources/pooledDataSource (unpooled-data-source spec) (map->properties pool-properties-map)))

  (^DataSource [driver spec pool-properties-map]
   (DataSources/pooledDataSource (unpooled-data-source driver spec) (map->properties pool-properties-map))))

(defn connection-pool-spec
  "Create a new connection pool for a JDBC `spec` and return a spec for it. Optionally pass a map of connection pool
  properties -- see https://www.mchange.com/projects/c3p0/#configuration_properties for a description of valid options
  and their default values."
  ([spec]
   {:datasource (pooled-data-source spec)})

  ([spec pool-properties-map]
   {:datasource (pooled-data-source spec pool-properties-map)})

  ([driver spec pool-properties-map]
   {:datasource (pooled-data-source driver spec pool-properties-map)}))

(defn pooled-data-source-from-url
  "Create a new pooled DataSource from a JDBC URL string."
  (^DataSource [url]
   (DataSources/pooledDataSource (proxy-data-source url nil)))

  (^DataSource [url pool-properties-map]
   (DataSources/pooledDataSource (proxy-data-source url nil) (map->properties pool-properties-map)))

  (^DataSource [driver url pool-properties-map]
   (DataSources/pooledDataSource (proxy-data-source driver url nil) (map->properties pool-properties-map))))

(defn destroy-connection-pool!
  "Immediately release all resources held by a connection pool."
  [spec-or-data-source]
  (cond
    (map? spec-or-data-source)
    (recur (:datasource spec-or-data-source))

    (instance? DataSource spec-or-data-source)
    (DataSources/destroy ^DataSource spec-or-data-source)

    :else
    (throw
     (IllegalArgumentException.
      "Don't know how to destroy conn pool: expected JDBC spec with `:datasource` or instance of `DataSource`."))))
