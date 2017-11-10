
package data_structure;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Word = feature : occurrence 
 * This object also stores the total contribution of this Word
 * contribution: total number of other Words that coexist with it
 * contribution determines the priority of this word
 * @author Ting Xie
 *
 */

public class Word implements Comparable<Word>, Serializable{
	/**
	 * 
	 */
	private static final long serialVersionUID = 3255595030419463421L;
	
	private int featureID;
    private int totalContribution;
    private int occurrence;
    private int hashcode;
    
	private Word(int featureID,int occurrence){
		this.featureID=featureID;
		this.totalContribution=0;
		this.occurrence=occurrence;
		//create hashcode
		ArrayList<Integer> list=new ArrayList<Integer>();
		list.add(this.featureID);
		list.add(this.occurrence);
		this.hashcode=list.hashCode();
	}
	
	public int getFeatureID(){
		return this.featureID;
	}
	
	public int getOccurrence(){return this.occurrence;}
	
    public Integer getContribution(){
   	 return this.totalContribution;
    }

    
    public static Word createNewWord(int featureID,int occurrence,HashMap<Integer,HashMap<Integer,Word>> featureMap){   	
    	HashMap<Integer,Word> map=featureMap.get(featureID);
    	Word it;   	
    	if (map==null){
    		it=new Word(featureID,occurrence);
    		//register in featuremap
               map=new HashMap<Integer,Word>();
               map.put(occurrence,it);
    		   featureMap.put(featureID, map);
    	}
    	else {
    		it=map.get(occurrence);
    		if (it ==null){
    			it=new Word(featureID,occurrence);
    			map.put(occurrence,it);
    		}
    	}
		return it;  	
    }
 
    /**
     * updates total contribution of this sortablefeature
     */
    public void resetContribution(){
   	    this.totalContribution=0;   	   
    }


	@Override
	public boolean equals(Object o){
		Word feature=(Word) o;
		if (this.featureID==feature.featureID&&this.occurrence==feature.occurrence) return true;
		else return false;	
	}
	
	@Override
	public int hashCode(){		
		return this.hashcode;
	}
	
	
	@Override
	//need to consider question mark and longer features
	public int compareTo(Word arg0) {
		if (this.totalContribution>arg0.getContribution())
		return -1;
		else if (this.totalContribution<arg0.getContribution())
			return 1;
		else {
			int occur1=this.occurrence;
			int occur2=arg0.getOccurrence();
			if(occur1>occur2)
				return 1;
			else if (occur1<occur2)
				return -1;
			else if(this.featureID>arg0.featureID)
				return -1;
			else if (this.featureID<arg0.featureID)
				return 1;
			else
				return 0;
		}			
	}
	
	
	@Override
	public String toString(){
		return this.featureID+GlobalVariables.OccurSeparator+this.occurrence;		
	}
	
	public static String turnIDToString(int featureID,int occur){
		return featureID+GlobalVariables.OccurSeparator+occur;
	}
	
	public void addContribution(int a) {
		this.totalContribution+=a;		
	}
	
	
}
