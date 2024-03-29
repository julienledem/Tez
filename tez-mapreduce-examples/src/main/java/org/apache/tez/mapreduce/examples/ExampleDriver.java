/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.mapreduce.examples;

import org.apache.hadoop.util.ProgramDriver;
import org.apache.tez.mapreduce.examples.terasort.TeraGen;
import org.apache.tez.mapreduce.examples.terasort.TeraSort;
import org.apache.tez.mapreduce.examples.terasort.TeraValidate;

/**
 * A description of an example program based on its class and a 
 * human-readable description.
 */
public class ExampleDriver {
  
  public static void main(String argv[]){
    int exitCode = -1;
    ProgramDriver pgd = new ProgramDriver();
    try {
      pgd.addClass("wordcount", WordCount.class, 
          "A map/reduce program that counts the words in the input files.");
      pgd.addClass("wordcountmrrtest", WordCountMRRTest.class, 
          "A map/reduce program that counts the words in the input files."
          + " Map splits on spaces. First reduce splits on \".\"");
      pgd.addClass("randomwriter", RandomWriter.class, 
          "A map/reduce program that writes 10GB of random data per node.");
      pgd.addClass("randomtextwriter", RandomTextWriter.class, 
      "A map/reduce program that writes 10GB of random textual data per node.");
      pgd.addClass("sort", Sort.class,
          "A map/reduce program that sorts the data written by the random"
          + " writer.");
      pgd.addClass("secondarysort", SecondarySort.class,
          "An example defining a secondary sort to the reduce.");
      pgd.addClass("join", Join.class,
          "A job that effects a join over sorted, equally partitioned"
          + " datasets");
      pgd.addClass("teragen", TeraGen.class,
          "Generate data for the terasort");
      pgd.addClass("terasort", TeraSort.class,
          "Run the terasort");
      pgd.addClass("teravalidate", TeraValidate.class,
          "Checking results of terasort");
      pgd.addClass("groupbyorderbymrrtest", GroupByOrderByMRRTest.class,
          "A map-reduce-reduce program that does groupby-order by. Takes input"
          + " containing employee_name department name per line of input"
          + " and generates count of employees per department and"
          + " sorted on employee count");
      exitCode = pgd.run(argv);
    }
    catch(Throwable e){
      e.printStackTrace();
    }
    
    System.exit(exitCode);
  }
}
	
