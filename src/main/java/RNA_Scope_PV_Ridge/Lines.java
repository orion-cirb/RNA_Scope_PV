/*
 * #%L
 * Ridge Detection plugin for ImageJ
 * %%
 * Copyright (C) 2014 - 2015 Thorsten Wagner (ImageJ java plugin), 1996-1998 Carsten Steger (original C code), 1999 R. Balasubramanian (detect lines code to incorporate within GRASP)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package ridge;

import java.util.ArrayList;
import java.util.Iterator;

public class Lines extends ArrayList<Line> {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private int frame;
	
	/**
	 * 
	 * @param frame Slice number
	 */
	public Lines(int frame) {
		// TODO Auto-generated constructor stub
		this.frame = frame;
	}
	
	public int getFrame(){
		return frame;
	}
	
	public int getIndexByID(int id){
		for(int i = 0; i < this.size(); i++){
			if(this.get(i).getID()==id){
				return i;
			}
		}
		return -1;
	}
}
