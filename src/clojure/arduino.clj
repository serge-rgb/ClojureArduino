(ns arduino
  (:import [gnu.io CommPortIdentifier SerialPort SerialPortEvent])
;  (:use [penumbra.opengl])
  (:require [clojure.java.io :as io]
	    [clojure.stacktrace :as ss])
  (:gen-class))

(def last-line (atom nil));last line read.
(def raw-read (atom [])) ;;Points to data being read.

(def accel (atom [0 0 0])) ;;Most current acceleration read.

(defn parse-input
  "Called by event listener to update the accel atom"
  [s]
  (if (= (count s) 6) ;;The arduino should be sending us 6 characters.
    (let [[x-l x-h
	   y-l y-h
	   z-l z-h] s
	   ;;Function to convert two bytes into a 2-byte number.
	   max-uchar (dec (Math/pow 2 16))
	   coerce (fn [a b]
		    (let [num (+ (bit-shift-left (int a) 8)
				 (int b))]
		      ;;16 bit signed integer within a 32 bit integer... fix this
		      (if (>= num max-uchar) 
			(- num max-uchar)
			num)))
	   x (coerce x-h x-l)
	   y (coerce y-h y-l)
	   z (coerce z-h z-l)]
      (compare-and-set! accel  @accel [x y z]))))

(defn get-port [^String name]
  (let [port-enum (CommPortIdentifier/getPortIdentifiers)
	port-list (loop [p port-enum
			 list []]
		    (if-not (. p hasMoreElements)
		      list			     
		      (recur p (conj list (. p nextElement)))))
	^CommPortIdentifier port-id  (first (filter #(= (.getName %1) name) port-list))]
    
    (if-not port-id
      (do (println "Port" name "not found."
		   "Here is a list of known ports:\n"
		   "--------------------")
	  (doseq [i (map #(.getName %) port-list)] (println i))
	  (println "--------------------")		   
	  (throw (new Exception "Port not found.")))      
      
      ;;Else, Return new configured,open port      
      (let [_ (println "Found port" name "... Opening...")
	    ^SerialPort port (.open port-id "Arduino" 2000)
	    listener (proxy [gnu.io.SerialPortEventListener
			     Runnable] []
		       (serialEvent [^gnu.io.SerialPortEvent ev]
				    (when (= (.getEventType ev)
					     SerialPortEvent/DATA_AVAILABLE)
				      ;;read data available.
				      (let [input-stream (.getInputStream port)
					    bytes-avail (.available input-stream)
					    buffer (byte-array bytes-avail)
					    bytes-read (.read input-stream buffer)] 
					(swap! raw-read concat (seq buffer))
					(swap! last-line (fn [a] (->> @raw-read
									   (reverse)
									   (drop-while #(not= (int \newline) %))
									   (drop 2) ;;carrier return
									   (take-while #(not= (int \newline)  %))
									   (reverse)))))
				      (parse-input @last-line))))]
	(doto port
	  (. addEventListener listener)
	  (. notifyOnDataAvailable true)	    
	  (. setSerialPortParams 9600 SerialPort/DATABITS_8
	     SerialPort/STOPBITS_1
	     SerialPort/PARITY_NONE))))))

(defn wait-for-line []
  (loop [line @last-line]
      (when-not line
	(Thread/sleep 100)
	(recur @last-line))))


(defmacro with-arduino
  "Open port. Wait until arduino starts sending us data. Evaluate forms"
  [port-name & forms]
  `(with-open [port# (get-port ~port-name)]
     (wait-for-line)
     ~@forms
     ;;clear everything
     (compare-and-set! last-line @last-line nil)))

  
(defn -main []
  (with-arduino "/dev/ttyUSB0"
    (doseq [i (range 400)]
      (println @accel)
      (Thread/sleep 25))))

